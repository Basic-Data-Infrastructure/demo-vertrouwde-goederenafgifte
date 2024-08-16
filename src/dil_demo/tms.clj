;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.tms
  (:require [clojure.tools.logging.readable :as log]
            [dil-demo.ishare.client :as ishare-client]
            [dil-demo.ishare.policies :as policies]
            [dil-demo.events :as events]
            [dil-demo.store :as store]
            [dil-demo.otm :as otm]
            [dil-demo.tms.web :as tms.web]
            [dil-demo.web-utils :as w]))

(defn- ishare-exec! [{:keys [client-data] :as req} title cmd]
  (binding [ishare-client/log-interceptor-atom (atom [])]
    (try
      (let [result (:ishare/result (ishare-client/exec (merge client-data cmd)))]
        (-> req
            (w/append-explanation [title {:ishare-log @ishare-client/log-interceptor-atom}])
            (assoc :ishare/result result)))
      (catch Exception ex
        (log/error ex)
        (-> req
            (w/append-explanation [title {:ishare-log @ishare-client/log-interceptor-atom}])
            (assoc-in [:flash :error] (str "Fout bij uitvoeren van iShare commando " (:ishare/message-type ex))))))))



(defn- ->ar-type
  [{{:ishare/keys [authentication-registry-type]} :client-data} & _]
  authentication-registry-type)

(defn- ishare-create-policy-command
  [{{:ishare/keys [client-id]} :client-data} subject trip effect]
  {:pre [(#{"Deny" "Permit"} effect)]}
  {:ishare/message-type :ishare/policy
   :ishare/params       (policies/->delegation-evidence
                         {:issuer  client-id
                          :subject subject
                          :target  (policies/->delegation-target (:ref trip))
                          :date    (-> trip :load :date)
                          :effect  effect})})

(defmulti delete-policy-for-trip! ->ar-type)

(defmethod delete-policy-for-trip! :poort8
  [{:keys [::store/store] :as req} {:keys [id] :as _trip} _subject-fn]
  (if-let [policy-id (get-in store [:trip-policies id :policy-id])]
    (-> req
        (ishare-exec! "Verwijder policy voor trip"
                      {:ishare/message-type :poort8/delete-policy
                       :ishare/params       {:policyId policy-id}

                       ;; ignore HTTP errors for when policy already
                       ;; deleted
                       :throw false})
        (update-in [::store/commands] (fnil conj [])
                   [:delete! :trip-policies id]))
    req))

(defmethod delete-policy-for-trip! :ishare
  [req trip subject-fn]
  ;; iSHARE does not return a policy-id upon creation for deletion so
  ;; we're going to force a "deny" policy in to make sure we don't
  ;; have a lingering "permit".
  (ishare-exec! req
                "Verwijder policy voor trip"
                (ishare-create-policy-command req (subject-fn trip) trip "Deny")))

(defmulti create-policy-for-trip! ->ar-type)

(defmethod create-policy-for-trip! :poort8
  [req {:keys [id] :as trip} subject-fn]
  (let [cmd
        {:ishare/message-type :poort8/policy
         :ishare/params
         (policies/->poort8-policy
          {:consignment-ref (:ref trip)
           :date            (-> trip :load :date)
           :subject         (subject-fn trip)})}
        res (ishare-exec! req "Toevoegen policy voor trip" cmd)]

    (if-let [policy-id (get-in res [:ishare/result "policyId"])]
      ;; poort8 AR will return a policy-id which can be used to delete the policy
      (update-in res [::store/commands] (fnil conj [])
                 [:put! :trip-policies {:id id, :policy-id policy-id}])
      res)))

(defmethod create-policy-for-trip! :ishare
  [req trip subject-fn]
  (ishare-exec! req "Toevoegen policy voor trip"
                (ishare-create-policy-command req (subject-fn trip) trip "Permit")))

(defmulti delegation-effect!
  "Apply delegation effects from store commands."
  (fn [_req [cmd type & _args]] [cmd type]))

(defmethod delegation-effect! [:delete! :trips]
  [{:keys [::store/store] :as req} [_ _ id]]
  (if-let [{:keys [driver-id-digits license-plate]
            :as   trip} (get-in store [:trips id])]
    (cond-> req
      (and driver-id-digits license-plate)
      (delete-policy-for-trip! trip policies/pickup-access-subject)

      :and
      (delete-policy-for-trip! trip policies/outsource-pickup-access-subject))
    req))

(defmethod delegation-effect! [:put! :trips]
  [{:keys [::store/store] :as req} [_ _ trip]]
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
  (let [;; remove already existing policy
        res (delete-policy-for-trip! req trip policies/outsource-pickup-access-subject)]

    ;; create outsource policy, TODO allow receiving TMS to subscribe to event topic
    (create-policy-for-trip! res trip policies/outsource-pickup-access-subject)))

;; do nothing for other store commands
(defmethod delegation-effect! :default [req & _] req)



(defn- wrap-delegation
  "Create policies in AR when trip is stored."
  [app {:keys [client-data]}]
  (fn delegation-wrapper [{::store/keys [store] :as req}]
    (let [{::store/keys [commands] :as res} (app req)]
      (reduce delegation-effect!
              (assoc res
                     :client-data client-data
                     ::store/store store)
              commands))))

(defn make-handler [config]
  (-> (tms.web/make-handler config)
      (wrap-delegation config)))

(defn base-event-handler
  [{:keys                         [::store/store subscription] :as event
    {:keys [bizStep disposition]} :event-data}]
  (let [ref (second subscription)]
    (when-let [trip (tms.web/get-trip-by-ref store ref)]
      (when (and (= bizStep "departing")
                 (= disposition "in_transit"))
        (assoc event ::store/commands
               [[:put! :trips (assoc trip :status otm/status-in-transit)]])))))

(defn make-event-handler [{:keys [client-data] :as _config}]
  (-> base-event-handler
      (events/wrap-fetch-event client-data)))
