;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms.verify
  (:require [dil-demo.ishare.policies :as policies]
            [org.bdinetwork.ishare.client :as ishare-client]))

(defn ishare-get-delegation-evidence!
  [{:keys [client-data] :as req}
   title
   {:keys [issuer target mask]}]
  (binding [ishare-client/log-interceptor-atom (atom [])]
    (try
      (-> req
          (update :delegation-evidences (fnil conj [])
                  {:issuer issuer
                   :target target

                   :delegation-evidence
                   (-> client-data
                       (assoc :ishare/policy-issuer issuer ;; ensures we target the right AR
                              :ishare/message-type :delegation
                              :ishare/params mask)
                       (ishare-client/exec)
                       :ishare/result
                       :delegationEvidence)})
          (update-in [:explanation] (fnil conj [])
                     [title {:ishare-log @ishare-client/log-interceptor-atom}]))
      (catch Throwable ex
        (-> req
            (update :delegation-evidences (fnil conj [])
                    {:issuer issuer
                     :target target})
            (update-in [:explanation] (fnil into [])
                       [[title {:ishare-log @ishare-client/log-interceptor-atom}
                         [(str "Technische fout upgrade: " (.getMessage ex))]]]))))))

(defn rejection-reasons [{:keys [delegation-evidences]}]
  (seq (mapcat (fn [{:keys [delegation-evidence target]}]
                 (policies/rejection-reasons delegation-evidence target))
               delegation-evidences)))

(defn rejection-eori [{:keys [delegation-evidences]}]
  (->> delegation-evidences
       (filter (fn [{:keys [delegation-evidence target]}]
                 (policies/rejection-reasons delegation-evidence target)))
       (first)
       :issuer))

(defn permitted? [req]
  (not (rejection-reasons req)))

(defn verify-owner!
  "Ask AR of owner if carrier is allowed to pickup order. Return list of
  rejection reasons or nil, if access is allowed."
  [req transport-order {:keys [carrier-eoris]}]
  {:pre [(seq carrier-eoris)]}

  (let [issuer  (-> transport-order :owner :eori)
        ref     (:ref transport-order)
        target  (policies/->delegation-target ref)
        subject (policies/outsource-pickup-access-subject {:ref     ref
                                                           :carrier {:eori (first carrier-eoris)}})
        mask    (policies/->delegation-mask {:issuer  issuer
                                             :subject subject
                                             :target  target})]
    (ishare-get-delegation-evidence! req
                                     "Verifieer bij verlader"
                                     {:issuer issuer, :target target, :mask mask})))

(defn verify-carriers!
  "Ask AR of carriers if sourced to next or, if last, driver is allowed
  to pickup order. Return list of rejection reasons or nil, if access
  is allowed."
  [req transport-order {:keys [carrier-eoris driver-id-digits license-plate]}]
  {:pre [(seq carrier-eoris) driver-id-digits license-plate]}

  (let [ref    (:ref transport-order)
        target (policies/->delegation-target ref)]
    (loop [carrier-eoris carrier-eoris
           req           req]
      (if (and (seq carrier-eoris)
               (permitted? req))
        (let [carrier-eori (first carrier-eoris)
              pickup?      (= 1 (count carrier-eoris))
              subject      (if pickup?
                             (policies/pickup-access-subject {:driver-id-digits driver-id-digits
                                                              :license-plate    license-plate
                                                              :carrier          {:eori carrier-eori}})
                             (policies/outsource-pickup-access-subject {:ref          ref
                                                                        :carrier {:eori (second carrier-eoris)}}))
              mask         (policies/->delegation-mask {:issuer  carrier-eori
                                                        :subject subject
                                                        :target  target})]
          (recur (next carrier-eoris)
                 (ishare-get-delegation-evidence! req
                                                  (if pickup?
                                                    "Verifieer ophalen bij vervoerder"
                                                    "Verifieer uitbesteding bij vervoerder")
                                                  {:issuer carrier-eori
                                                   :target target
                                                   :mask   mask})))
        req))))

(defn verify!
  [client-data transport-order params]
  (-> {:client-data client-data}
      (verify-owner! transport-order params)
      (verify-carriers! transport-order params)))
