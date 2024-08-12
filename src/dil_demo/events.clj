;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.events
  (:require [babashka.http-client.websocket :as ws]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging.readable :as log]
            [dil-demo.events.web :as events.web]
            [dil-demo.ishare.client :as ishare-client]
            [dil-demo.web-utils :as w])
  (:import (java.util Base64)
           (java.time Instant)))

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

(defn- get-token
  [{:keys                     [client-data]
    {:keys [token-endpoint
            token-server-id]} :pulsar}]
  {:pre [client-data token-endpoint token-server-id]}
  (-> client-data
      (assoc :ishare/message-type :access-token
             :ishare/endpoint token-endpoint
             :ishare/server-id token-server-id)
      ishare-client/exec
      :ishare/result))

(defn- make-on-message-handler
  [{:keys [eori events-atom] :as config}
   [owner-eori topic]]
  {:pre [config owner-eori topic]}

  (let [subscription [owner-eori topic eori]]
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
            (let [{:keys [messageId] :as event}
                  (-> msg
                      (str)
                      (json/read-str :key-fn keyword)
                      (update :payload (comp json/read-str decode-base64)))]
              (log/debug "Received event" subscription event)
              (swap! events-atom assoc messageId [subscription event]))
            (catch Throwable e
              (log/error "Parsing message failed" subscription e))))))))

;; live websockets
(defonce websockets-atom (atom nil))

(defn close-websockets!
  "Close all currently opened websockets."
  []
  (doseq [[k websocket] @websockets-atom]
    (try
      (let [ws @websocket]
        (log/debug "Closing websocket" ws)
        (ws/close! ws))
      (catch Throwable e
        (log/debug "Close websocket for" k "failed;" e)))
    (swap! websockets-atom dissoc k)))

(defn subscribe!
  "Subscribe to websocket for `[owner-eori topic]`.  Authorization
  should already been secured by owner."
  [{:keys [eori subscriptions-atom] {:keys [url]} :pulsar :as config}
   [owner-eori topic]]
  {:pre [url config owner-eori topic]}
  (let [subscription [owner-eori topic eori]
        open-websocket
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

            :on-message (make-on-message-handler config [owner-eori topic])

            :on-close (fn on-close [_ws status reason]
                        (log/debug "Consumer websocket closed" subscription status reason)

                        (when-not (= 1000 status) ;; 1000 = Normal Closure
                          (log/info "Restarting consumer for" subscription)

                          (Thread/sleep sleep-before-reconnect-msec)
                          (swap! websockets-atom assoc subscription (open-websocket))))

            :on-error (fn on-error [_ws err]
                        (log/info "Consumer error for" subscription err)

                        (Thread/sleep sleep-before-reconnect-msec)
                        (swap! websockets-atom assoc subscription (open-websocket)))}))]

    ;; register subscription for reconnection after restart
    (swap! subscriptions-atom conj subscription)

    ;; register websocket for shutdown
    (swap! websockets-atom assoc subscription (open-websocket))))

(defn unsubscribe!
  "Unsubscribe (and close)."
  [{:keys [subscriptions-atom]}
   [owner-eori topic]]
  (let [subscription [owner-eori topic]]
    (swap! subscriptions-atom disj subscription)
    (when-let [websocket (get @websockets-atom subscription)]
      (ws/close! @websocket)
      (swap! websockets-atom dissoc subscription))))

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
    (ws/send! ws (json/json-str {:payload payload}))
    (ws/close! ws)))



(defn- load-atom [file-name empty-val]
  (let [f (io/file file-name)
        a (atom (if (.exists f) (edn/read-string (slurp f)) empty-val))]
    (add-watch a nil
               (fn [_key _ref old new]
                 (when (not= old new)
                   (spit (io/file file-name) (pr-str new)))))
    a))

(defn- load-events-atom [eori] ;; FIXME, use separate environment per "user-number"
  (let [file-name (str "/tmp/dil-demo-events-" eori ".edn")] ;; TODO config
    (load-atom file-name {})))

(defn- load-subscriptions-atom [eori] ;; FIXME, use separate environment per "user-number"
  (let [file-name (str "/tmp/dil-demo-subscription-" eori ".edn")] ;; TODO config
    (load-atom file-name #{})))



(defn- -wrap
  [app {:keys [eori pulsar client-data]}]
  (let [events-atom        (load-events-atom eori)
        subscriptions-atom (load-subscriptions-atom eori)

        config {:client-data        client-data
                :eori               eori
                :pulsar             pulsar
                :events-atom        events-atom
                :subscriptions-atom subscriptions-atom}]

    ;; reopen websockets
    (doseq [subscription @subscriptions-atom]
      (subscribe! config subscription))

    (fn events-wrapper [req]
      (let [{::keys [commands] :as res} (-> req
                                            (assoc :events @events-atom)
                                            (app))]
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
                 (assoc-in [:flash :error] "Aanmaken AR policy mislukt")

                 :and
                 (w/append-explanation ["Toevoegen policy toegang event broker"
                                        {:ishare-log log}])))

             :subscribe!
             (let [{:keys [owner-eori topic]} opts]
               (subscribe! config [owner-eori topic])
               (w/append-explanation res
                                     [(str "Geabonneerd op '" owner-eori "#" topic "'")]))

             :unsubscribe!
             (let [{:keys [owner-eori topic]} opts]
               (unsubscribe! config [owner-eori topic])
               (w/append-explanation res
                                     [(str "Abonnement '" owner-eori "#" topic "' opgegeven")]))

             :send!
             (let [{:keys [owner-eori topic message]} opts]
               (send-message! config [owner-eori topic] message)
               (w/append-explanation res
                                     [(str "Bericht gestuurd naar '" owner-eori "#" topic "' gestuurd") {:event message}]))))
         res
         commands)))))

(defn wrap
  "Ring middleware providing event access."
  [app config]
  (-> app
      (events.web/wrap config)
      (-wrap config)))
