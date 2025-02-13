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

(defn equipment-gate-in?
  [event]
  (= ["EQUIPMENT" "GTIN"] (event-type event)))

(defn equipment-loaded?
  [event]
  (= ["EQUIPMENT" "LOAD"] (event-type event)))

(defn transport-departed?
  [event]
  (= ["TRANSPORT" "DEPA"] (event-type event)))

(defn tstamp
  [{{tstamp "eventDateTime"} "payload"}]
  tstamp)
