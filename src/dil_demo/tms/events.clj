;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.tms.events
  (:require [dil-demo.otm :as otm]
            [dil-demo.tms.web :as web]))

(defn handler
  [{:keys                         [store subscription] :as event
    {:keys [bizStep disposition]} :event-data}]
  (let [[_ ref user-number site-id] subscription]
    (when-let [trip (web/get-trip-by-ref store ref)]
      (when (and (= bizStep "departing")
                 (= disposition "in_transit"))
        (-> event
            (update :store/commands conj
                    [:put! :trips (assoc trip :status otm/status-in-transit)])
            (update :event/commands conj
                    [:unsubscribe! (web/trip->subscription trip
                                                           user-number
                                                           site-id)]))))))

(defn resubscribe-commands
  "Collect event subscribe commands for still pending trips."
  [{:keys                      [site-id store]
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
