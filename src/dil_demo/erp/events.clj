;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.erp.events
  (:require [dil-demo.erp.web :as web]
            [dil-demo.otm :as otm]))

(defn handler
  [{:keys                         [store subscription] :as event
    {:keys [bizStep disposition]} :event-data}]
  (let [[_ ref user-number site-id] subscription]
    (when-let [consignment (web/get-consignment-by-ref store ref)]
      (when (and (= bizStep "departing")
                 (= disposition "in_transit"))
        (-> event
            (update :store/commands conj
                    [:put! :consignments (assoc consignment :status otm/status-in-transit)])
            (update :event/commands conj
                    [:unsubscribe! (web/consignment->subscription consignment
                                                                  user-number
                                                                  site-id)]))))))

(defn resubscribe-commands
  "Collect event subscribe commands for still pending consignments."
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
                           :consignments
                           vals
                           (filter #(= otm/status-requested (:status %)))))))
       (map (fn [sub] [:subscribe! sub]))
       seq))
