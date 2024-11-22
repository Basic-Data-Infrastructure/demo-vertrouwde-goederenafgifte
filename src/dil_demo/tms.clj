;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.tms
  (:require [clojure.tools.logging.readable :as log]
            [dil-demo.i18n :refer [t]]
            [dil-demo.ishare.policies :as policies]
            [dil-demo.tms.web :as tms.web]
            [dil-demo.web-utils :as w]
            [org.bdinetwork.ishare.client :as ishare-client]
            [org.bdinetwork.ishare.client.interceptors :refer [log-interceptor-atom]]))

(defn- ishare-exec! [req title cmd]
  (binding [log-interceptor-atom (atom [])]
    (try
      (let [result (:ishare/result (ishare-client/exec cmd))]
        (-> req
            (w/append-explanation [title {:ishare-log @log-interceptor-atom}])
            (assoc :ishare/result result)))
      (catch Exception ex
        (log/error ex)
        (-> req
            (w/append-explanation [title {:ishare-log @log-interceptor-atom}])
            (assoc-in [:flash :error] (t "error/ishare-command-failed"
                                         {:error (:ishare/operation ex)})))))))



(defn- ->ar-type
  [{{:ishare/keys [authorization-registry-type]} :client-data} & _]
  authorization-registry-type)

(defn- ishare-create-policy-command
  [{:ishare/keys [client-id] :as client-data} subject trip effect]
  {:pre [(#{"Deny" "Permit"} effect)]}
  (policies/ishare-policy-request client-data (policies/->delegation-evidence
                                               {:issuer  client-id
                                                :subject subject
                                                :target  (policies/->delegation-target (:ref trip))
                                                :date    (-> trip :load :date)
                                                :effect  effect})))

(defmulti delete-policy-for-trip! ->ar-type)

(defmethod delete-policy-for-trip! :poort8
  [{:keys [store client-data] :as req} {:keys [id] :as _trip} _subject-fn]
  (if-let [policy-id (get-in store [:trip-policies id :policy-id])]
    (-> req
        (ishare-exec! (t "explanation/remove-policy-for-trip")
                      (-> (policies/poort8-delete-policy-request client-data policy-id)
                          ;; ignore HTTP errors for when policy already
                          ;; deleted
                          (assoc :throw false)))
        (update :store/commands (fnil conj [])
                [:delete! :trip-policies id]))
    ;; just return req if no policy exists
    req))

(defmethod delete-policy-for-trip! :ishare
  [{:keys [client-data] :as req} trip subject-fn]
  ;; iSHARE does not return a policy-id upon creation for deletion so
  ;; we're going to force a "deny" policy in to make sure we don't
  ;; have a lingering "permit".
  (ishare-exec! req
                (t "explanation/remove-policy-for-trip")
                (ishare-create-policy-command client-data (subject-fn trip) trip "Deny")))

(defmulti create-policy-for-trip! ->ar-type)

(defmethod create-policy-for-trip! :poort8
  [{:keys [client-data] :as req} {:keys [id] :as trip} subject-fn]
  (let [cmd
        (policies/poort8-policy-request client-data
                                        (policies/->poort8-policy
                                         {:consignment-ref (:ref trip)
                                          :date            (-> trip :load :date)
                                          :subject         (subject-fn trip)}))
        res (ishare-exec! req (t "explanation/add-policy-for-trip") cmd)]

    (if-let [policy-id (get-in res [:ishare/result "policyId"])]
      ;; poort8 AR will return a policy-id which can be used to delete the policy
      (update res :store/commands (fnil conj [])
              [:put! :trip-policies {:id id, :policy-id policy-id}])
      res)))

(defmethod create-policy-for-trip! :ishare
  [{:keys [client-data] :as req} trip subject-fn]
  (ishare-exec! req
                (t "explanation/add-policy-for-trip")
                (ishare-create-policy-command client-data (subject-fn trip) trip "Permit")))

(defmulti delegation-effect!
  "Apply delegation effects from store commands."
  (fn [_req [cmd type & _args]] [cmd type]))

(defmethod delegation-effect! [:delete! :trips]
  [{:keys [store] :as req} [_ _ id]]
  (if-let [{:keys [driver-id-digits license-plate]
            :as   trip} (get-in store [:trips id])]
    (cond-> req
      (and driver-id-digits license-plate)
      (delete-policy-for-trip! trip policies/pickup-access-subject)

      :and
      (delete-policy-for-trip! trip policies/outsource-pickup-access-subject))
    req))

(defmethod delegation-effect! [:put! :trips]
  [{:keys [store] :as req} [_ _ trip]]
  (let [old-trip (get-in store [:trips (:id trip)])]

    (cond-> req
      ;; delete pre existing driver/pickup policy
      (and (:driver-id-digits old-trip) (:license-plate old-trip))
      (delete-policy-for-trip! old-trip policies/pickup-access-subject)

      ;; create driver/pickup policy
      (and (:driver-id-digits trip) (:license-plate trip))
      (create-policy-for-trip! trip policies/pickup-access-subject))))

(defmethod delegation-effect! [:publish! :trips]
  [req [_ _ _ trip]]
  (-> req
      ;; remove already existing policy
      (delete-policy-for-trip! trip policies/outsource-pickup-access-subject)
      (create-policy-for-trip! trip policies/outsource-pickup-access-subject)))

;; do nothing for other store commands
(defmethod delegation-effect! :default [req & _] req)



(defn- wrap-delegation
  "Create policies in AR when trip is stored."
  [app {:keys [client-data]}]
  (fn delegation-wrapper [{:keys [store] :as req}]
    (let [{:store/keys [commands] :as res} (app req)]
      (reduce delegation-effect!
              (assoc res
                     :client-data client-data
                     store store)
              commands))))

(defn make-web-handler [config]
  (-> (tms.web/make-handler config)
      (wrap-delegation config)))
