;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.epcis)

(defn ->departing
  [{:keys [id tstamp location]}]
  {"type"        "ObjectEvent"
   "eventTime"   tstamp
   "eventId"     id
   "action"      "OBSERVE"
   "disposition" "in_transit"
   "bizStep"     "departing"
   "bizLocation" location})

(defn departing?
  [{:strs [disposition bizStep]}]
  (and (= disposition "in_transit")
       (= bizStep "departing")))

(defn ->arriving
  [{:keys [id tstamp location]}]
  {"type"        "ObjectEvent"
   "eventTime"   tstamp
   "eventId"     id
   "action"      "OBSERVE"
   "disposition" "in_transit"
   "bizStep"     "arriving"
   "bizLocation" location})

(defn arriving?
  [{:strs [disposition bizStep]}]
  (and (= disposition "in_transit")
       (= bizStep "arriving")))

(defn ->loading
  [{:keys [id tstamp location]}]
  {"type"        "ObjectEvent"
   "eventTime"   tstamp
   "eventId"     id
   "action"      "OBSERVE"
   "disposition" "in_transit"
   "bizStep"     "loading"
   "bizLocation" location})

(defn loading?
  [{:strs [disposition bizStep]}]
  (and (= disposition "in_transit")
       (= bizStep "loading")))
