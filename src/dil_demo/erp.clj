;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.erp
  (:require [clojure.core.match :refer [match]]
            [clojure.tools.logging :as log]
            [dil-demo.dcsa-events-connector :as dcsa-events-connector]
            [dil-demo.erp.web :as erp.web]
            [dil-demo.events :as events]
            [dil-demo.i18n :refer [t]]
            [dil-demo.ishare.policies :as policies]
            [dil-demo.portbase :as portbase]
            [dil-demo.store :as store]
            [dil-demo.web-utils :as w]
            [org.bdinetwork.ishare.client :as ishare-client]
            [org.bdinetwork.ishare.client.interceptors :refer [log-interceptor-atom]]))

(defn- map->delegation-evidence
  [client-id effect {:keys [ref load] :as obj}]
  {:pre [client-id effect ref load]}
  (policies/->delegation-evidence
   {:issuer  client-id
    :subject (policies/outsource-pickup-access-subject obj)
    :target  (policies/->delegation-target ref)
    :date    (:date load)
    :effect  effect}))

(defn- ->ishare-ar-policy-request
  [{:ishare/keys [client-id] :as client-data} effect obj]
  (policies/ishare-policy-request client-data
                                  (map->delegation-evidence client-id
                                                            effect
                                                            obj)))

(defn- ishare-ar-create-policy! [client-data effect obj]
  (binding [log-interceptor-atom (atom [])]
    [(try (-> client-data
              (->ishare-ar-policy-request effect obj)
              (ishare-client/exec))
          (catch Throwable ex
            (log/error ex)
            false))
     @log-interceptor-atom]))

(defn- wrap-policy-deletion
  "When a trip is deleted, retract existing policies in the AR."
  [app {:keys [client-data]}]
  (fn policy-deletion-wrapper
    [{:keys [store] :as req}]

    (let [{:store/keys [commands] :as res} (app req)]
      ;; TODO do not try to delete policies of draft consignments (they do not have them)
      (if-let [id (-> (filter #(= [:delete! :consignments] (take 2 %))
                              commands)
                      (first)
                      (nth 2))]
        (let [old-consignment (get-in store [:consignments id])

              [result log]
              ;; kinda hackish way to delete a policy from a iSHARE AR
              (ishare-ar-create-policy! client-data "Deny" old-consignment)]
          (cond->
              (w/append-explanation res [(t "explanation/ishare/delete-policy") {:ishare-log log}])

            (not result)
            (assoc-in [:flash :error] (t "error/delete-policy-failed"))))

        res))))

(defn wrap-delegation
  "Create policies in AR when trip is published."
  [app {:keys [client-data]}]
  (fn delegation-wrapper [req]
    (let [{:store/keys [commands] :as res} (app req)
          trip (->> commands
                    (filter #(= [:publish! :trips] (take 2 %)))
                    (map #(nth % 3))
                    (first))]
      (if trip
        (let [[result log]
              (ishare-ar-create-policy! client-data "Permit" trip)]
          (cond->
              (w/append-explanation res
                                    [(t "erp/explanation/pickup-delegation") {:ishare-log log}])

            (not result)
            (assoc-in [:flash :error] (t "error/create-policy-failed"))))
        res))))

(defn make-web-handler [config]
  (-> (erp.web/make-handler config)

      (store/wrap-truncate :consignments config)
      (events/wrap-auto-unsubscribe :consignments
                                    erp.web/consignment->subscription
                                    config)

      (wrap-policy-deletion config)
      (wrap-delegation config)

      (events/wrap-web config)
      (store/wrap config)))

(defn- get-consigment [store ref]
  (as-> store $
    (get $ :consignments)
    (map val $)
    (filter #(= ref (:ref %)) $)
    (first $)))

(defmulti handle-dcsa-event
  (fn [_res _store [_order-ref event]]
    (dcsa-events-connector/event-type event)))

(defmethod handle-dcsa-event  ["EQUIPMENT" "GTIN"]
  [res store [order-ref _event]]
  (if-let [consignment (get-consigment store order-ref)]
    (update res :store/commands (fnil conj [])
            [:put! :consignments (assoc consignment :status "TODO-GTIN")])
    res))

(defmethod handle-dcsa-event  ["EQUIPMENT" "LOAD"]
  [res store [order-ref _event]]
  (if-let [consignment (get-consigment store order-ref)]
    (update res :store/commands (fnil conj [])
            [:put! :consignments (assoc consignment :status "TODO-LOAD")])
    res))

(defmethod handle-dcsa-event  ["TRANSPORT" "DEPA"]
  [res store [order-ref _event]]
  (if-let [consignment (get-consigment store order-ref)]
    (update res :store/commands (fnil conj [])
            [:put! :consignments (assoc consignment :status "TODO-DEPA")])
    res))

(defn wrap-incoming-portbase-event [app]
  (fn incoming-portbase-event-wrapper [{:keys [store] :as req}]
    (let [{:keys [dcsa-events-connector/events] :as res} (app req)]
      (reduce (fn [res event]
                (handle-dcsa-event res store event))
              res
              events))))



(defn cli [{:keys [base-url portbase]} & args]
  (match [args]
    [(["portbase-subscribe" user-number] :seq)]
    (prn (portbase/subscribe! (assoc portbase
                                     :base-url base-url) user-number))

    [(["portbase-unsubscribe" subscription-id] :seq)]
    (prn (portbase/unsubscribe! portbase subscription-id))

    [(["portbase-subscriptions"] :seq)]
    (prn (portbase/get-subscriptions portbase))))
