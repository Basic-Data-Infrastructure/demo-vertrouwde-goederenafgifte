;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.events
  (:require [babashka.http-client.websocket :as ws]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [dil-demo.events.web :as events.web]
            [dil-demo.i18n :refer [t]]
            [dil-demo.store :as store]
            [dil-demo.web-utils :as w]
            [org.bdinetwork.ishare.client :as ishare-client])
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
  [{:keys                                             [client-data pulsar]
    {:ishare/keys [authentication-registry-id
                   authentication-registry-endpoint]} :client-data}
   [owner-eori topic]
   read-eoris write-eoris]
  {:pre [ ;; only owner can authorize access
         (= owner-eori (:ishare/client-id client-data))]}

  (binding [ishare-client/log-interceptor-atom (atom [])]
    [(try
       (let [read-eoris  (set read-eoris)
             write-eoris (set write-eoris)
             token       (-> client-data
                             (assoc :ishare/endpoint     authentication-registry-endpoint
                                    :ishare/server-id    authentication-registry-id
                                    :ishare/message-type :access-token)
                             ishare-client/exec
                             :ishare/result)
             now-secs    (unix-epoch-secs)]
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
                                   :policies [{:target {:resource {:type        "http://rdfs.org/ns/void#Dataset"
                                                                   :identifiers [(str owner-eori "#" topic)]
                                                                   :attributes  ["*"]}
                                                        :actions  actions
                                                        :environment {:serviceProviders [(:token-server-id pulsar)]}}
                                               :rules  [{:effect "Permit"}]}]}]}]
              (-> {:ishare/bearer-token token
                   :ishare/endpoint     authentication-registry-endpoint
                   :ishare/server-id    authentication-registry-id
                   :ishare/message-type :ishare/policy
                   :ishare/params       {:delegationEvidence delegation-evidence}}
                  ishare-client/exec
                  :ishare/result)))))
       (catch Throwable ex
         (log/error ex)
         false))
     @ishare-client/log-interceptor-atom]))

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
  [{:keys                     [client-data]
    {:keys [token-endpoint
            token-server-id]} :pulsar}]
  {:pre [client-data token-endpoint token-server-id]}
  (retrying-call
   #(-> client-data
        (assoc :ishare/message-type :access-token
               :ishare/endpoint token-endpoint
               :ishare/server-id token-server-id)
        ishare-client/exec
        :ishare/result)))

(defn- make-on-message-handler
  [{:keys [eori] :as config}
   [owner-eori topic user-number :as subscription] callback-fn]
  {:pre [config owner-eori topic]}

  (fn on-message-handler [ws msg _last?]
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
              (try (callback-fn event)
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
  [{:keys [eori] {:keys [url]} :pulsar :as config}
   [owner-eori topic _user-number _site-id :as subscription] callback-fn]
  {:pre [url config owner-eori topic]}
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
            :on-open (fn on-open [_ws]
                       (log/debug "Consumer websocket open" subscription))

            :on-message (make-on-message-handler config subscription callback-fn)

            :on-close (fn on-close [ws status reason]
                        (log/debug "Consumer websocket closed" subscription status reason)

                        (when-not (= 1000 status) ;; 1000 = Normal Closure
                          (log/info "Restarting consumer for" subscription)

                          (try (ws/close! ws) (catch Throwable _)) ;; make sure it's really closed
                          (Thread/sleep sleep-before-reconnect-msec)
                          (swap! websockets-atom assoc subscription (open-websocket))))

            :on-error (fn on-error [ws err]
                        (log/info "Consumer error for" subscription err)

                        ;; just close it and try again
                        (try (ws/close! ws) (catch Throwable _))
                        (Thread/sleep sleep-before-reconnect-msec)
                        (swap! websockets-atom assoc subscription (open-websocket)))}))]

    ;; register websocket for shutdown
    (swap! websockets-atom assoc subscription (open-websocket))))

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
  "Send message to `[owner-eori topic]`.  Authorization should already
  been secured by owner."
  [{{:keys [url]} :pulsar :as config}
   [owner-eori topic] message]
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
        (ws/close! ws)))))



(defn exec! [res config callback-fn]
  (reduce
   (fn [res [cmd & [opts]]]
     (log/debug "handling" cmd opts)

     (case cmd
       :authorize!
       (let [{:keys [owner-eori topic
                     read-eoris write-eoris]} opts

             [result log]
             (authorize! config [owner-eori topic] read-eoris write-eoris)]
         (cond-> res
           (not result)
           (assoc-in [:flash :error] (t "error/create-policy-failed"))

           :and
           (w/append-explanation [(t "explanation/events/access-policy"
                                     {:ishare-log log})])))

       :subscribe!
       (let [{:keys [owner-eori topic user-number site-id]} opts]
         (subscribe! config [owner-eori topic user-number site-id] callback-fn)
         (w/append-explanation res
                               [(t "explanation/events/subscribe"
                                   {:owner-eori owner-eori, :topic topic})]))

       :unsubscribe!
       (let [{:keys [owner-eori topic user-number site-id]} opts]
         (unsubscribe! config [owner-eori topic user-number site-id])
         (w/append-explanation res
                               [(t "explanation/events/unsubscribe"
                                   {:owner-eori owner-eori, :topic topic})]))

       :send!
       (let [{:keys [owner-eori topic message]} opts]
         (send-message! config [owner-eori topic] message)
         (w/append-explanation res
                               [(t "explanation/events/message-sent"
                                   {:owner-eori owner-eori, :topic topic})
                                {:event message}]))))
   res
   (::commands res)))

(defn wrap
  "Ring middleware providing event command processing."
  [app {:keys [eori pulsar client-data]} callback-fn]
  (let [config {:client-data        client-data
                :eori               eori
                :pulsar             pulsar}]
    (fn events-wrapper [req]
      (-> req
          (app)
          (exec! config callback-fn)))))

(defn wrap-web
  "Ring middleware providing pulse access and event command processing."
  [app config callback-fn]
  (-> app
      (events.web/wrap config)
      (wrap config callback-fn)))

(defn wrap-fetch-and-store-event
  "Wrapper for event handlers to automatically fetch event data from
  remote services and store it."
  [f client-data]
  (fn fetch-event-wrapper [{:keys [subscription messageId] :as req}]
    (let [pulse (select-keys req [:properties
                                  :payload
                                  :publishTime
                                  :redeliveryCount
                                  :messageId])]
      (if (get-in req [::store/store :pulses messageId])
        (do
          (log/debug "Skipping pulse, already seen" {:subscription subscription
                                                     :pulse        pulse})
          nil)
        (do
          (log/debug "Processing pulse" {:subscription subscription
                                         :pulse        pulse})
          (-> req
              (assoc :event-data
                     (-> pulse
                         :payload
                         (events.web/fetch-event client-data)
                         :body
                         (json/read-str :key-fn keyword)
                         (assoc :subscription subscription)))
              (update ::store/commands conj
                      [:put! :pulses (assoc pulse :id messageId)])
              (f)))))))
