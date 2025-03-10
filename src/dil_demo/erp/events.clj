;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.erp.events
  (:require [dil-demo.epcis :as epcis]
            [dil-demo.erp.web :as web]
            [dil-demo.events.pulsar :as events.pulsar]
            [dil-demo.otm :as otm]))

(defn handler
  [{:keys [store subscription event-data] :as event}]
  (let [[_ ref user-number site-id] subscription]
    (when-let [consignment (web/get-consignment-by-ref store ref)]
      (cond
        (epcis/departing? event-data)
        (-> event
            ;; Received from warehouse to shipment is on its way..
            (update :store/commands conj
                    [:put! :consignments (assoc consignment :status otm/status-in-transit)]))

        (epcis/arriving? event-data)
        (-> event
            ;; Receive from portbase when first leg to harbor finished
            ;; but still in in-transit.  Not expecting any more events
            ;; in this implementation fase
            (update :event/commands conj
                    [:unsubscribe! (events.pulsar/->subscription consignment
                                                                 user-number
                                                                 site-id)]))))))

(defn resubscribe-commands
  "Collect event subscribe commands for still pending consignments."
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
                           :consignments
                           vals
                           (filter #(= otm/status-requested (:status %)))))))
       (map (fn [sub] [:subscribe! sub]))
       seq))
