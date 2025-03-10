;;; SPDX-FileCopyrightText: 2024, 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024, 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.erp.api
  (:require [compojure.core :refer [context routes]]
            [dil-demo.dcsa-events-connector :as dcsa-events-connector]
            [dil-demo.events.api :as events.api]
            [dil-demo.events.pulsar :as pulsar]
            [dil-demo.portbase :as portbase]))

(defn- get-consignment [store ref]
  (as-> store $
    (get $ :consignments)
    (map val $)
    (filter #(= ref (:ref %)) $)
    (first $)))

(defn- handle-dcsa-event
  "Put incoming DCSA event in associated consignments."
  [res store [order-ref event]]
  (if-let [consignment (get-consignment store order-ref)]
    (update res :store/commands (fnil conj [])
            [:put! :consignments (update consignment :dcsa-events (fnil conj [])
                                         event)])
    res))

(defn- wrap-incoming-portbase-event
  "Put incoming DCSA events in associated consignments."
  [app]
  (fn incoming-portbase-event-wrapper [{:keys [store] :as req}]
    (let [{:keys [dcsa-events-connector/events] :as res} (app req)]
      (reduce (fn [res [order-ref event]]
                (handle-dcsa-event res store [order-ref event]))
              res
              events))))

(defn- wrap-propagate-portbase-event-to-pulsar
  [f config]
  (fn propagate-event-to-pulsar-wrapper [{:keys [store] :as req}]
    (let [{:keys [dcsa-events-connector/events] :as res} (f req)]
      (reduce (fn [res [order-ref event]]
                (if-let [consignment (get-consignment store order-ref)]
                  (events.api/apply-epcis-event res
                                                config
                                                req
                                                {:order   consignment
                                                 :event   (dcsa-events-connector/->epcis event)
                                                 :targets #{(-> consignment :carrier :eori)}})
                  res))
              res
              events))))

(defn make-handler
  "Make an API endpoint to handle events.

  - serve EPCIS events as published via Pulsar
  - handle incoming DCSA events"
  [config]
  (routes
   (context (str "/" events.api/event-path) []
     (events.api/make-handler config))
   (context (str "/" dcsa-events-connector/webhook-path) []
     (-> (dcsa-events-connector/make-handler config)
         (dcsa-events-connector/wrap-event-handler)
         (dcsa-events-connector/wrap-webhook-subscription-handler config)
         (portbase/wrap-subscription-execution (:portbase config))
         (wrap-incoming-portbase-event)
         (wrap-propagate-portbase-event-to-pulsar config)
         (pulsar/wrap-exec-commands config)))))
