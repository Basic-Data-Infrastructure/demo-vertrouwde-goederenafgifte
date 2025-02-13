;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.events.pulsar
  (:require [babashka.http-client.websocket :as ws]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [dil-demo.events.web :as events.web]
            [dil-demo.i18n :refer [t]]
            [dil-demo.ishare.policies :as policies]
            [dil-demo.web-utils :as w]
            [nl.jomco.http-status-codes :as http-status]
            [org.bdinetwork.ishare.client :as ishare-client]
            [org.bdinetwork.ishare.client.interceptors :refer [log-interceptor-atom]]
            [org.bdinetwork.ishare.client.request :as request])
  (:import (java.time Instant)
           (java.util Base64)))

(defn- encode-base64 [^String s]
  (String. (.encode (Base64/getEncoder) (.getBytes s))))

(defn- decode-base64 [^String s]
  (String. (.decode (Base64/getDecoder) (.getBytes s))))

(def sleep-before-reconnect-msec 2000)

(def policy-ttl-secs (* 60 60 24 7)) ;; one week

(def policy-tolerance-secs 60) ;; one minute



(defn- unix-epoch-secs []
  (.getEpochSecond (Instant/now)))

(defn authorize!
  "Setup delegation policies to allow access to topic."
  [{{:ishare/keys [client-id
                   authorization-registry-id
                   authorization-registry-base-url]
     :as          client-data} :client-data
    {:keys [token-server-id]} :pulsar}
   [owner-eori topic]
   read-eoris write-eoris]
  {:pre [ ;; only owner can authorize access
         (= owner-eori (:ishare/client-id client-data))]}

  (binding [log-interceptor-atom (atom [])]
    [(try
       (let [read-eoris  (set read-eoris)
             write-eoris (set write-eoris)
             token       (-> client-data
                             (assoc :ishare/base-url     authorization-registry-base-url
                                    :ishare/server-id    authorization-registry-id)
                             (request/access-token-request)
                             ishare-client/exec
                             :ishare/result)
             now-secs    (unix-epoch-secs)]

         (assert (= owner-eori client-id) "only owner can authorize access")

         (doall
          (for [eori (into read-eoris write-eoris)]
            ;; TODO make ishare/p8 AR agnostic
            (let [actions
                  (cond-> []
                    (contains? read-eoris eori)  (conj "BDI.subscribe")
                    (contains? write-eoris eori) (conj "BDI.publish"))

                  delegation-evidence
                  {:notBefore    (- now-secs policy-tolerance-secs)
                   :notOnOrAfter (+ now-secs policy-ttl-secs policy-tolerance-secs)
                   :policyIssuer owner-eori
                   :target       {:accessSubject (str eori "#" owner-eori "#" topic)}
                   :policySets   [{:target   {:environment {:licenses ["ISHARE.0001"]}}
                                   :policies [{:target {:resource    {:type        "http://rdfs.org/ns/void#Dataset"
                                                                      :identifiers [(str owner-eori "#" topic)]
                                                                      :attributes  ["*"]}
                                                        :actions     actions
                                                        :environment {:serviceProviders [token-server-id]}}
                                               :rules  [{:effect "Permit"}]}]}]}]
              (-> client-data
                  (assoc :ishare/bearer-token token
                         :ishare/base-url     authorization-registry-base-url
                         :ishare/server-id    authorization-registry-id)
                  (policies/ishare-policy-request {:delegationEvidence delegation-evidence})
                  ishare-client/exec
                  :ishare/result)))))
       (catch Throwable ex
         (log/error ex)
         false))
     @log-interceptor-atom]))

(defn retrying-call [f & {:keys [max-retries wait-msec]
                          :or {max-retries 5, wait-msec 2000}}]
  (try
    (f)
    (catch Exception ex
      (if (pos? max-retries)
        (do
          (log/debug "Retrying after" ex)
          (Thread/sleep wait-msec)
          (retrying-call f :max-retries (dec max-retries) :wait-msec wait-msec))
        (throw ex)))))

(defn- get-token
  [{:keys                               [client-data]
    {:keys [token-endpoint
            token-server-id]} :pulsar}]
  {:pre [client-data token-endpoint token-server-id]}
  (retrying-call
   #(-> client-data
        (assoc :ishare/base-url token-endpoint
               :ishare/server-id token-server-id)
        (request/access-token-request)
        ishare-client/exec
        :ishare/result)))

(defn- make-on-message-callback
  "Wrap `handler` into a callback for on-message events from web sockets.

  This callback responds to `AUTH_CHALLENGE` message and regular
  \"pulses\" which will be decoded, enhanced with receiver information
  and passed to `handler`."
  [{:keys [eori] :as config}
   [owner-eori topic user-number :as subscription] handler]
  {:pre [config owner-eori topic]}

  (fn on-message-callback [ws msg _last?]
    (let [{:strs [type]} (json/read-str (str msg))]
      (if (= "AUTH_CHALLENGE" type)
        (let [token (get-token config)]
          (log/debug "Responding to AUTH_CHALLENGE" subscription)
          (->> {:type         "AUTH_RESPONSE"
                :authResponse {:clientVersion   "v21"
                               :protocolVersion 21
                               :response        {:authMethodName "token"
                                                 :authData       token}}}
               (json/json-str)
               (ws/send! ws)))
        (try
          (let [event (-> msg
                          (str)
                          (json/read-str :key-fn keyword)
                          (update :payload (comp json/read-str decode-base64)))]
            (log/debug "Received event" subscription event)

            (let [event (assoc event
                               :owner-eori owner-eori
                               :eori eori
                               :user-number user-number
                               :subscription subscription)]
              (try (handler event)
                   (catch Throwable e
                     (log/error "Handling event failed"
                                e
                                event)))))
          (catch Throwable e
            (log/error "Parsing message failed" subscription e)))))))

;; live websockets
(defonce websockets-atom (atom nil))

(defn subscribe!
  "Subscribe to websocket for `[owner-eori topic user-number site-id]`.
  Authorization should already been secured by owner."
  [{:keys [eori]
    {:keys [url disabled]} :pulsar
    :as config}
   [owner-eori topic _user-number _site-id :as subscription] handler]
  {:pre [url config owner-eori topic]}
  (when-not disabled
    (let [open-websocket
          (fn open-websocket []
            (log/info "Starting consumer" subscription)

            (ws/websocket
             {:headers {"Authorization" (str "Bearer " (get-token config))}
              :uri     (str url
                            "consumer/persistent/public/"
                            owner-eori
                            "/"
                            topic
                            "/"
                            eori)

              :async   true ;; returns a future ws
              :on-open (fn on-open-callback [_ws]
                         (log/debug "Consumer websocket open" subscription))

              :on-message (make-on-message-callback config subscription handler)

              :on-close (fn on-close-callback [ws status reason]
                          (log/debug "Consumer websocket closed" subscription status reason)

                          (when-not (= 1000 status) ;; 1000 = Normal Closure
                            (log/info "Restarting consumer for" subscription)

                            (try (ws/close! ws) (catch Throwable _)) ;; make sure it's really closed
                            (Thread/sleep sleep-before-reconnect-msec)
                            (swap! websockets-atom assoc subscription (open-websocket))))

              :on-error (fn on-error-callback [ws err]
                          (log/info "Consumer error for" subscription err)

                          ;; just close it and try again
                          (try (ws/close! ws) (catch Throwable _))
                          (Thread/sleep sleep-before-reconnect-msec)
                          (swap! websockets-atom assoc subscription (open-websocket)))}))]

      ;; register websocket for shutdown
      (swap! websockets-atom assoc subscription (open-websocket)))))

(defn unsubscribe!
  "Unsubscribe (and close)."
  [_ subscription]
  (when-let [websocket (get @websockets-atom subscription)]
    (try
      (let [ws @websocket]
        (log/debug "Closing websocket" ws)
        (ws/close! ws))
      (catch Throwable e
        (log/debug "Close websocket for" subscription "failed;" e)))
    (swap! websockets-atom dissoc subscription)))

(defn close-websockets!
  "Close all currently opened websockets."
  []
  (doseq [[subscription _] @websockets-atom]
    (unsubscribe! nil subscription)))

(defn send-message!
  "Send message to `[owner-eori topic]`.

  Authorization should already been secured by owner."
  [{{:keys [url disabled]} :pulsar :as config}
   [owner-eori topic] message]
  (when-not disabled
    (let [ws      (ws/websocket
                   {:headers {"Authorization" (str "Bearer " (get-token config))}
                    :uri     (str url
                                  "producer/persistent/public/"
                                  owner-eori
                                  "/"
                                  topic)})
          payload (-> message
                      (json/json-str)
                      (encode-base64))]
      (try
        (ws/send! ws (json/json-str {:payload payload}))
        (finally
          (ws/close! ws))))))



(defmulti exec! (fn [command & _] (first command)))

(defmethod exec! :authorize!
  [[_ {:keys [owner-eori topic read-eoris write-eoris]}] config & [res] ]
  (let [[result log] (authorize! config [owner-eori topic] read-eoris write-eoris)]
    (when res
      (cond-> res
        (not result)
        (assoc-in [:flash :error] (t "error/create-policy-failed"))

        :and
        (w/append-explanation [(t "explanation/events/access-policy")
                               {:ishare-log log}])))))

(defmethod exec! :subscribe!
  [[_ {:keys [owner-eori topic user-number site-id]}]
   {{{:keys [handler]} :events} :resources :as config}
   & [res]]
  (subscribe! config
              [owner-eori topic user-number site-id]
              (fn user-number-site-id-wrapper [event]
                (handler (assoc event
                                :user-number user-number
                                :site-id site-id))))
  (when res
    (w/append-explanation res
                          [(t "explanation/events/subscribe"
                              {:owner-eori owner-eori, :topic topic})])))

(defmethod exec! :unsubscribe!
  [[_ {:keys [owner-eori topic user-number site-id]}] config & [res]]
  (unsubscribe! config [owner-eori topic user-number site-id])

  (when res
    (w/append-explanation res
                          [(t "explanation/events/unsubscribe"
                              {:owner-eori owner-eori, :topic topic})])))

(defmethod exec! :send!
  [[_ {:keys [owner-eori topic message]}] config & [res]]
  (send-message! config [owner-eori topic] message)

  (when res
    (w/append-explanation res
                          [(t "explanation/events/message-sent"
                              {:owner-eori owner-eori, :topic topic})
                           {:event message}])))

(defn wrap-exec-commands
  "Ring middleware providing event command processing."
  [app config]
  (fn exec-commands-wrapper [req]
    (let [res (app req)]
      (reduce (fn exec-command [res cmd]
                (log/debug "handling" cmd)
                (exec! cmd config res))
              res
              (:event/commands res)))))

(defn wrap-fetch-and-store-event
  "Wrapper for event handlers to automatically fetch event data from remote services and store it."
  [f {:keys [client-data]}]
  (fn fetch-and-store-event-wrapper [{:keys [subscription messageId] :as req}]
    (when req
      (let [pulse (select-keys req [:properties
                                    :payload
                                    :publishTime
                                    :redeliveryCount
                                    :messageId])]
        (if (get-in req [:store :pulses messageId])
          (do
            (log/debug "Skipping pulse, already seen" {:subscription subscription
                                                       :pulse        pulse})
            nil)
          (do
            (log/debug "Processing pulse" {:subscription subscription
                                           :pulse        pulse})
            (let [event-res (-> pulse
                                :payload
                                (events.web/fetch-event client-data))]
              (when (= http-status/ok (:status event-res))
                (-> req
                    (assoc :event-data
                           (-> event-res
                               :body
                               (json/read-str :key-fn keyword)
                               (assoc :subscription subscription)))
                    (update :store/commands conj
                            [:put! :pulses (assoc pulse :id messageId)])
                    (f))))))))))



(defn ->subscription
  "A pulsar topic for the consigment/transport-order/trip."
  [{:keys [ref], {:keys [eori]} :owner}
   user-number
   site-id]
  {:topic       ref
   :owner-eori  eori
   :user-number user-number
   :site-id     site-id})

(defn wrap-auto-unsubscribe
  "Automatically unsubscribe from topic when deleted."
  [app resource {:keys [site-id]}]
  (fn auto-unsubscribe-wrapper [{:keys [store user-number] :as req}]
    (let [res     (app req)
          deleted (->> (:store/commands res)
                       (filter #(= [:delete! resource] (take 2 %)))
                       (map #(get-in store [resource (nth % 2)])))]
      (cond-> res
        (seq deleted)
        (update :event/commands concat
                (map #(vector :unsubscribe!
                              (->subscription % user-number site-id))
                     deleted))))))
