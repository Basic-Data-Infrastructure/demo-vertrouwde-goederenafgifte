;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms.verify
  (:require [dil-demo.i18n :refer [t]]
            [dil-demo.ishare.policies :as policies]
            [org.bdinetwork.ishare.client :as ishare-client]
            [org.bdinetwork.ishare.client.interceptors :refer [log-interceptor-atom]]
            [org.bdinetwork.ishare.client.request :as request]))

(defn ishare-get-delegation-evidence!
  [{:keys [client-data] :as req}
   title
   {:keys [issuer target mask]}]
  (binding [log-interceptor-atom (atom [])]
    (try
      (-> req
          (update :delegation-evidences (fnil conj [])
                  {:issuer issuer
                   :target target

                   :delegation-evidence
                   (-> client-data
                       (request/delegation-evidence-request mask)
                       (ishare-client/exec)
                       :ishare/result
                       :delegationEvidence)})
          (update :explanation (fnil conj [])
                     [title {:ishare-log @log-interceptor-atom}]))
      (catch Throwable ex
        (-> req
            (update :delegation-evidences (fnil conj [])
                    {:issuer issuer
                     :target target})
            (update :explanation (fnil into [])
                    [[title {:ishare-log @log-interceptor-atom}
                      [(t "wms/explanation/error" {:error (.getMessage ex)})]]]))))))

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

(defn owner-rejection-reasons
  "Ask AR of owner if carrier is allowed to pickup order.

  Return list of rejection reasons or nil, if access is allowed."
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
                                     (t "wms/explanation/verify-erp")
                                     {:issuer issuer, :target target, :mask mask})))

(defn carriers-rejection-reasons
  "Ask AR of carriers if sourced to next or, if last, driver is allowed to pickup order.

  Return list of rejection reasons or nil, if access is allowed."
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
                                                    (t "wms/explanation/verify-tms-pickup")
                                                    (t "wms/explanation/verify-tms-outsource"))
                                                  {:issuer carrier-eori
                                                   :target target
                                                   :mask   mask})))
        req))))

(defn verify!
  [client-data transport-order params]
  (-> {:client-data client-data}
      (owner-rejection-reasons transport-order params)
      (carriers-rejection-reasons transport-order params)))
