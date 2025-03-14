;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.tms.events
  (:require [dil-demo.epcis :as epcis]
            [dil-demo.events.pulsar :as events.pulsar]
            [dil-demo.otm :as otm]
            [dil-demo.tms.web :as web]))

(defn handler
  [{:keys [store subscription event-data] :as event}]
  (let [[_ ref user-number site-id] subscription]
    (when-let [trip (web/get-trip-by-ref store ref)]
      (cond
        (epcis/departing? event-data)
        (-> event
            (update :store/commands conj
                    [:put! :trips (assoc trip :status otm/status-in-transit)]))

        (epcis/arriving? event-data)
        (-> event
            (update :store/commands conj
                    [:put! :trips (assoc trip :status otm/status-completed)])
            (update :event/commands conj
                    [:unsubscribe! (events.pulsar/->subscription trip
                                                                 user-number
                                                                 site-id)]))))))

(defn resubscribe-commands
  "Collect event subscribe commands for still pending trips."
  [{:keys                      [site-id]
    {:keys [store]}            :resources
    {:ishare/keys [client-id]} :client-data}]
  (->> @store
       (mapcat (fn [[user-number user-store]]
                 (map (fn [{:keys [ref], {:keys [eori]} :owner}]
                        {:topic       ref
                         :owner-eori  eori
                         :user-number user-number
                         :site-id     site-id})
                      (->> (get user-store client-id)
                           :trips
                           vals
                           (filter #(= otm/status-assigned (:status %)))))))
       (map (fn [sub] [:subscribe! sub]))
       seq))
