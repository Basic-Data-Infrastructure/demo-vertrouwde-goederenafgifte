;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.portbase
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [dil-demo.i18n :refer [t]]
            [dil-demo.web-utils :as w]))

(defn- api-request!
  [{:keys [method path body]}
   {:keys [api-base-url api-key]}]
  (let [req (cond-> {:method  method
                     :uri     (str api-base-url path)
                     :headers (cond-> {"X-API-Key" api-key
                                       "Accept"    "application/json"}
                                body (assoc :content-type "application/json"))}
              body (assoc :body (json/encode body)))
        _   (log/debug "Sending request" req)

        res (http/request req)
        _   (log/debug "Got response" res)]
    res))



(defmulti request-event! :type)

(defmethod request-event! :equipment-gate-in
  [{:keys [equipment-reference
           location-short-name]}
   config]
  {:pre [equipment-reference location-short-name]}
  (api-request! {:method :put
                 :path   "/test-events/equipment-gate-in"
                 :body {:equipmentReference equipment-reference
                        :locationShortName  location-short-name}}
                config))

(defmethod request-event! :equipment-loaded
  [{:keys [equipment-reference
           location-short-name
           port-visit-reference]}
   config]
  {:pre [equipment-reference location-short-name port-visit-reference]}
  (api-request! {:method :put
                 :path "/test-events/equipment-loaded"
                 :body {:equipmentReference equipment-reference
                        :locationShortName  location-short-name
                        :portVisitReference port-visit-reference}}
                config))

(defmethod request-event! :transport-departed
  [{:keys [port-visit-reference]}
   config]
  {:pre [port-visit-reference]}
  (api-request! {:method :put
                 :path "/test-events/transport-departed"
                 :body {:portVisitReference port-visit-reference}}
                config))

(defn wrap-request-event
  [app config]
  (fn request-event-wrapper [req]
    (let [{:request-portbase/keys [events] :as res} (app req)]
      (reduce (fn [res event]
                (log/debug "Requesting portbase event" event)
                (let [r (request-event! event config)]
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

(defn subscribe!
  [config user-number]
  (let [webhook-url     (webhook-url config user-number)
        subscription-id (-> {:method :put
                             :path   "/v3/event-subscriptions"
                             :body   {:callbackURL webhook-url}}
                            (api-request! config)
                            :body
                            (json/parse-string)
                            (get "subscriptionId"))]
    (log/info "Registered webhook" {:webhook-url     webhook-url
                                    :subscription-id subscription-id})
    subscription-id))

(defn get-subscription
  [config subscription-id]
  (let [res (-> {:method :get
                 :path   (str "/v3/event-subscriptions/" subscription-id)}
                (api-request! config))]
    (log/debug "Got subscription" res)
    (-> res :body (json/parse-string))))

(defn get-subscriptions
  [config]
  (let [res (-> {:method :get
                 :path   (str "/v3/event-subscriptions")}
                (api-request! config))]
    (-> res :body (json/parse-string))))

(defn unsubscribe!
  [config subscription-id]
  (let [res (-> {:method :delete
                 :path   (str "/v3/event-subscriptions/" subscription-id)}
                (api-request! config))]
    (log/debug "Unsubscribed" res)
    (-> res :body (json/parse-string))))
