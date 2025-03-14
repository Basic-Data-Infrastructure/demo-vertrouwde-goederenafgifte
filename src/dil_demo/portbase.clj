;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.portbase
  (:require [babashka.http-client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [dil-demo.i18n :refer [t]]
            [dil-demo.web-utils :as w]))

(def x-api-key-header "X-API-Key")

(defn- api-request
  [{:keys [api-base-url api-key]}
   {:keys [method path body]}]
  (cond-> {:method  method
           :uri     (str api-base-url path)
           :headers (cond-> {x-api-key-header api-key
                             "Accept" "application/json"}
                      body (assoc :content-type "application/json"))}
    body (assoc :body (json/write-str body))))

(defn redact-response [res]
  (update-in res [:request :headers] assoc x-api-key-header "[REDACTED]"))



(defmulti request-event :type)

(defmethod request-event :equipment-gate-in
  [{:keys [equipment-reference
           location-short-name]}
   config]
  {:pre [equipment-reference location-short-name]}
  (api-request config
               {:method :put
                :path   "/test-events/equipment-gate-in"
                :body {:equipmentReference equipment-reference
                       :locationShortName  location-short-name}}))

(defmethod request-event :equipment-loaded
  [{:keys [equipment-reference
           location-short-name
           port-visit-reference]}
   config]
  {:pre [equipment-reference location-short-name port-visit-reference]}
  (api-request config
               {:method :put
                :path "/test-events/equipment-loaded"
                :body {:equipmentReference equipment-reference
                       :locationShortName  location-short-name
                       :portVisitReference port-visit-reference}}))

(defmethod request-event :transport-departed
  [{:keys [port-visit-reference vessel-imo-number]}
   config]
  {:pre [port-visit-reference vessel-imo-number]}
  (api-request config
               {:method :put
                :path   "/test-events/transport-departed"
                :body   {:portVisitReference port-visit-reference
                         :vesselIMONumber    vessel-imo-number}}))

(defn wrap-request-event
  [app config]
  (fn request-event-wrapper [req]
    (let [{:request-portbase/keys [events] :as res} (app req)]
      (reduce (fn [res event]
                (let [r (-> event
                            (request-event config)
                            (http/request)
                            (redact-response))]
                  (log/debug "Requested portbase event" r)
                  (w/append-explanation res
                                        [(t "explanation/request-portbase-event")
                                         {:http-request  (:request r)
                                          :http-response (dissoc r :request)}])))
              res
              events))))



(defn subscribe
  "Returns a subscribe request for webhook."
  [config {:keys [callback-url equipment-reference vessel-imo-number]}]
  {:pre [callback-url (or (and equipment-reference (not vessel-imo-number))
                          (and vessel-imo-number (not equipment-reference)))]}
  (api-request config
               {:method :put
                :path   "/v3/event-subscriptions"
                :body   (into {:callbackURL callback-url}
                              (if equipment-reference
                                {:equipmentReference equipment-reference}
                                {:vesselIMONumber vessel-imo-number}))}))

(defn get-subscription
  "Return request to get info about `subscription-id` webhook."
  [config subscription-id]
  (api-request config
               {:method :get
                :path   (str "/v3/event-subscriptions/" subscription-id)}))

(defn get-subscriptions
  "Return request to get all live webhook subscriptions."
  [config]
  (api-request config
               {:method :get
                :path   "/v3/event-subscriptions"}))

(defn unsubscribe
  "Return request to unsubscribe `subscription-id` webhook."
  [config subscription-id]
  (api-request config
               {:method :delete
                :path   (str "/v3/event-subscriptions/" subscription-id)}))



(defmulti exec (fn [_config _req _res [oper _args]] oper))

(defmethod exec :subscribe!
  [config _req res [_ args]]
  (let [r (-> config (subscribe args) (http/request) (redact-response))
        _ (log/debug "Subscribe" r)

        subscription-id (-> r
                            :body
                            (json/read-str)
                            (get "subscriptionId"))
        store-id        (dissoc args :callback-url)]

    (-> res
        (update :store/commands (fnil conj [])
                [:put! :portbase-subscriptions {:id              store-id
                                                :subscription-id subscription-id}])
        (w/append-explanation [(t "explanation/portbase-subscribe")
                               {:http-request  (:request r)
                                :http-response (dissoc r :request)}]))))

(defmethod exec :unsubscribe!
  [config {{:keys [portbase-subscriptions]} :store} res
   [_ store-id]]
  (let [subscription-id (get-in portbase-subscriptions [store-id :subscription-id])
        r               (-> config
                            (unsubscribe subscription-id)
                            (http/request)
                            (redact-response))]
    (log/debug "Unsubscribe" r)
    (-> res
        (update :store/commands (fnil conj [])
                [:delete! :portbase-subscriptions store-id])
        (w/append-explanation [(t "explanation/portbase-unsubscribe")
                               {:http-request  (:request r)
                                :http-response (dissoc r :request)}]))))

(defn wrap-subscription-execution
  "Execute subscription commands and return subscription state to store.

  Scope: UI, webhook"
  [f config]
  (fn subscription-execution-wrapper [req]
    (let [{:keys [portbase/commands] :as res} (f req)]
      (reduce (fn command-reducer [res cmd] (exec config req res cmd))
              res
              commands))))
