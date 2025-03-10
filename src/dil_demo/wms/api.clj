;;; SPDX-FileCopyrightText: 2024, 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024, 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms.api
  (:require [compojure.core :refer [context routes]]
            [dil-demo.epcis :as epcis]
            [dil-demo.events.api :as events.api]
            [dil-demo.otm :as otm])
  (:import (java.time Instant)
           (java.util UUID)))

(defn apply-epcis-departing-event
  "Add store and event commands to reflect the occurrence of a gate out."
  [res config req {:keys [load] :as transport-order}]
  (let [targets #{(-> transport-order :owner :eori)
                  (-> transport-order :carrier :eori)}
        event   (epcis/->departing {:id       (.toString (UUID/randomUUID))
                                    :tstamp   (Instant/now)
                                    :location (:location-name load)})]
    (-> res
        (events.api/apply-epcis-event config
                                      req
                                      {:order   transport-order
                                       :event   event
                                       :targets targets})
        (update :store/commands (fnil into [])
                [[:put! :transport-orders
                  (assoc transport-order :status otm/status-in-transit)]]))))

(defn make-handler
  "Make an API endpoint to handle events.

  - serve EPCIS events as published via Pulsar"
  [config]
  (routes
   (context (str "/" events.api/event-path) []
     (events.api/make-handler config))))
