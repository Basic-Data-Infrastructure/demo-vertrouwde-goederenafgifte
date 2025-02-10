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

(defn- api-request
  [{:keys [api-base-url api-key]}
   {:keys [method path body]}]
  (cond-> {:method  method
           :uri     (str api-base-url path)
           :headers (cond-> {"X-API-Key" api-key
                             "Accept"    "application/json"}
                      body (assoc :content-type "application/json"))}
    body (assoc :body (json/write-str body))))



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
  [{:keys [port-visit-reference]}
   config]
  {:pre [port-visit-reference]}
  (api-request config
               {:method :put
                :path "/test-events/transport-departed"
                :body {:portVisitReference port-visit-reference}}))

(defn wrap-request-event
  [app config]
  (fn request-event-wrapper [req]
    (let [{:request-portbase/keys [events] :as res} (app req)]
      (reduce (fn [res event]
                (log/debug "Requesting portbase event" event)
                (let [r (-> event
                            (request-event config)
                            (http/request))]
                  (w/append-explanation res
                                        [(t "explanation/request-portbase-event")
                                         {:http-request  (-> r
                                                             :request
                                                             (update :headers assoc "X-API-Key" "[REDACTED]"))
                                          :http-response (-> r
                                                             (dissoc :request))}])))
              res
              events))))



(defn webhook-url [{:keys [base-url]
                    {:keys [webhook-secret]} :portbase}
                   user-number]
  (str base-url "/" user-number "/erp/event/" webhook-secret))

(defn subscribe
  "Returns a subscribe request for webhook on `user-number`."
  [config user-number]
  (api-request config
               {:method :put
                :path   "/v3/event-subscriptions"
                :body   {:callbackURL (webhook-url config user-number)}}))

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
