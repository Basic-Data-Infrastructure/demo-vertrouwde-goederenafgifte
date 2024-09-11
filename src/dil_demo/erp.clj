;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.erp
  (:require [clojure.tools.logging :as log]
            [dil-demo.erp.web :as erp.web]
            [dil-demo.events :as events]
            [dil-demo.ishare.policies :as policies]
            [dil-demo.otm :as otm]
            [dil-demo.store :as store]
            [dil-demo.web-utils :as w]
            [org.bdinetwork.ishare.client :as ishare-client]))

(defn- map->delegation-evidence
  [client-id effect {:keys [ref load] :as obj}]
  {:pre [client-id effect ref load]}
  (policies/->delegation-evidence
   {:issuer  client-id
    :subject (policies/outsource-pickup-access-subject obj)
    :target  (policies/->delegation-target ref)
    :date    (:date load)
    :effect  effect}))

(defn- ->ishare-ar-policy-request [{:ishare/keys [client-id]
                                    :as          client-data}
                                   effect
                                   obj]
  (assoc client-data
         :ishare/message-type :ishare/policy
         :ishare/params (map->delegation-evidence client-id
                                                  effect
                                                  obj)))

(defn- ishare-ar-create-policy! [client-data effect obj]
  (binding [ishare-client/log-interceptor-atom (atom [])]
    [(try (-> client-data
              (->ishare-ar-policy-request effect obj)
              (ishare-client/exec))
          (catch Throwable ex
            (log/error ex)
            false))
     @ishare-client/log-interceptor-atom]))

(defn- wrap-policy-deletion
  "When a trip is deleted, retract existing policies in the AR."
  [app {:keys [client-data]}]
  (fn policy-deletion-wrapper
    [{:keys [::store/store] :as req}]

    (let [{::store/keys [commands] :as res} (app req)]
      (if-let [id (-> (filter #(= [:delete! :consignments] (take 2 %))
                              commands)
                      (first)
                      (nth 2))]
        (let [old-consignment (get-in store [:consignments id])

              [result log]
              ;; kinda hackish way to delete a policy from a iSHARE AR
              (ishare-ar-create-policy! client-data "Deny" old-consignment)]
          (cond->
              (w/append-explanation res ["Verwijderen policy" {:ishare-log log}])

            (not result)
            (assoc-in [:flash :error] "Verwijderen AR policy mislukt")))

        res))))

(defn wrap-delegation
  "Create policies in AR when trip is published."
  [app {:keys [client-data]}]
  (fn delegation-wrapper [req]
    (let [{::store/keys [commands] :as res} (app req)
          trip (->> commands
                    (filter #(= [:publish! :trips] (take 2 %)))
                    (map #(nth % 3))
                    (first))]
      (if trip
        (let [[result log]
              (ishare-ar-create-policy! client-data "Permit" trip)]
          (cond->
              (w/append-explanation res
                                    ["Toevoegen policy toestemming pickup" {:ishare-log log}])

            (not result)
            (assoc-in [:flash :error] "Aanmaken AR policy mislukt")))
        res))))

(defn make-handler [config]
  (-> (erp.web/make-handler config)
      (wrap-policy-deletion config)
      (wrap-delegation config)))


(defn base-event-handler
  [{:keys                         [::store/store subscription] :as event
    {:keys [bizStep disposition]} :event-data}]
  (let [[_ ref user-number site-id] subscription]
    (when-let [consignment (erp.web/get-consignment-by-ref store ref)]
      (when (and (= bizStep "departing")
                 (= disposition "in_transit"))
        (-> event
            (update ::store/commands conj
                    [:put! :consignments (assoc consignment :status otm/status-in-transit)])
            (update ::events/commands conj
                    [:unsubscribe! (erp.web/consignment->subscription consignment
                                                                      user-number
                                                                      site-id)]))))))

(defn- subscribe-commands
  "Collect event subscribe commands for still pending consignments."
  [{:keys                      [site-id store-atom]
    {:ishare/keys [client-id]} :client-data}]
  (->> @store-atom
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

(defn make-event-handler [{:keys [client-data] :as config}]
  (let [handler (events/wrap-fetch-and-store-event base-event-handler
                                                   client-data)]
    (events/exec! {::events/commands (subscribe-commands config)}
                  config
                  (store/wrap handler config))
    handler))
