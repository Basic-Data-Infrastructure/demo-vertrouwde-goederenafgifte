;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.erp
  (:require [clojure.tools.logging :as log]
            [dil-demo.erp.web :as erp.web]
            [dil-demo.i18n :refer [t]]
            [dil-demo.ishare.policies :as policies]
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
      (if-let [id (-> (filter #(= [:delete! :consignments] (take 2 %))
                              commands)
                      (first)
                      (nth 2))]
        (let [old-consignment (get-in store [:consignments id])

              [result log]
              ;; kinda hackish way to delete a policy from a iSHARE AR
              (ishare-ar-create-policy! client-data "Deny" old-consignment)]
          (cond-> (w/append-explanation res [(t "explanation/ishare/delete-policy") {:ishare-log log}])
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

;; TODO need auto authorized-and-subscribe too?
(defn wrap-auto-unsubscribe
  "Automatically unsubscribe from order topic when deleted."
  [app {:keys [site-id]}]
  (fn auto-unsubscribe-wrapper [{:keys [store user-number] :as req}]
    (let [res                  (app req)
          deleted-consignments (->> (:store/commands res)
                                    (filter #(= [:delete! :consignments] (take 2 %)))
                                    (map #(erp.web/get-consignment store (nth % 2))))]
      (cond-> res
        (seq deleted-consignments)
        (update :event/commands concat
                (map #(vector :unsubscribe!
                              (erp.web/consignment->subscription % user-number site-id))
                     deleted-consignments))))))

(defn wrap-truncate-consignments
  "Automatically delete consignments when number in store is about `max-orders`."
  [app {:keys [max-orders]}]
  (fn truncate-consignments-wrapper [req]
    (let [consignments    (-> req :store :consignments)
          delete-commands (when (> (count consignments) max-orders)
                                (->> consignments
                                     (sort-by :ref)
                                     (reverse)
                                     (drop max-orders)
                                     (map #(vector :delete! :consignments (first %)))))]
      (cond-> (app req)
        (seq delete-commands)
        (update :store/commands concat delete-commands)))))

(defn make-web-handler [config]
  (-> (erp.web/make-handler config)
      (wrap-truncate-consignments config)
      (wrap-auto-unsubscribe config)
      (wrap-policy-deletion config)
      (wrap-delegation config)))
