;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.dcsa)

(defn event-type
  [{{event-type "eventType"} "metadata"
    {equipment-event-type-code "equipmentEventTypeCode"
     transport-event-type-code "transportEventTypeCode"} "payload"}]
  [event-type (or equipment-event-type-code
                  transport-event-type-code)])

(def equipment-gate-in-type ["EQUIPMENT" "GTIN"])
(def equipment-loaded-type ["EQUIPMENT" "LOAD"])
(def transport-departed-type ["TRANSPORT" "DEPA"])

(defn equipment-gate-in? [event]
  (= equipment-gate-in-type (event-type event)))

(defn equipment-loaded? [event]
  (= equipment-loaded-type (event-type event)))

(defn transport-departed? [event]
  (= transport-departed-type (event-type event)))

(defn event-id [event]
  (get-in event ["metadata" "eventID"]))

(defn tstamp [event]
  (get-in event ["payload" "eventDateTime"]))

(defn location [event]
  (get-in event ["payload" "transportCall" "location" "locationName"]))

(defn equipment-reference [event]
  (get-in event ["payload" "equipmentReference"]))

(defn port-visit-reference [event]
  (get-in event ["payload" "transportCall" "portVisitReference"]))

(defn vessel-imo-number [event]
  (get-in event ["payload" "transportCall" "vessel" "vesselIMONumber"]))
