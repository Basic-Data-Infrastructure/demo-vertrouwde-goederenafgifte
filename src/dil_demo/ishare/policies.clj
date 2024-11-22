;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.ishare.policies
  (:require [dil-demo.i18n :refer [t]])
  (:import (java.time Instant LocalDate LocalDateTime ZoneId)
           java.time.format.DateTimeFormatter))

;; https://ishare.eu/licenses/
(def license "ISHARE.0001") ;; FEEDBACK waarom deze "Re-sharing with Adhering Parties only"?

(defn ->delegation-target
  [consignment-ref]
  {:resource    {:type        "consignment-ref"
                 :identifiers [consignment-ref]
                 :attributes  ["*"]} ;; FEEDBACK deze resource is natuurlijk een hack
   :actions     ["BDI.PICKUP"]})

(defn local-date-time->epoch [^LocalDateTime dt]
  (-> dt
      (.atZone (ZoneId/systemDefault))
      (.toInstant)
      (.getEpochSecond)))

(defn epoch->local-date-time [^long secs]
  (-> secs
      (Instant/ofEpochSecond)
      (LocalDateTime/ofInstant (ZoneId/systemDefault))))

(defn local-date-time->str [^LocalDateTime dt]
  (.format dt DateTimeFormatter/ISO_LOCAL_DATE_TIME))

(defn epoch->str [^long secs]
  (-> secs
      (epoch->local-date-time)
      (local-date-time->str)))

(defn date->not-before-not-on-or-after [date]
  (let [date (LocalDate/parse date)
        not-before (.atStartOfDay date)
        not-on-or-after (.plusDays not-before 1)]
    {:notBefore    (local-date-time->epoch not-before)
     :notOnOrAfter (local-date-time->epoch not-on-or-after)}))

(defn ->delegation-evidence
  [{:keys [date issuer subject target effect]}]
  {:pre [date issuer subject target (#{"Permit" "Deny"} effect)]}
  {:delegationEvidence
   (merge
    {:policyIssuer issuer
     :target       {:accessSubject subject}
     :policySets   [{:target   {:environment {:licenses [license]}}
                     :policies [{:target target
                                 :rules  [{:effect effect}]}]}]}
    (date->not-before-not-on-or-after date))})


;; FEEDBACK: we gebruiken nu voor access subject voor chauffeur ID de
;; laatste cijfers van rijbewijs + kenteken van trekker.  dit is niet
;; compliant met iSHARE spec -- daar zou het altijd een iSHARE
;; identifier moeten zijn maar
;;
;; 1. ishare identificeert alleen rechtspersonen met een
;; EORI (bedrijven en instellingen)
;;
;; 2. ishare deelnemers zijn ook alleen rechtspersonen en iSHARE heeft
;; eigenlijk geen concept van personen die individueel authorisaties
;; krijgen.

;; FEEDBACK: resource id is nu een "plat" opdrachtnummer, maar dit zou
;; een URN moeten zijn.
;;
;; https://ishare-3.gitbook.io/ishare-trust-framework-collection/readme/detailed-descriptions/technical/structure-of-delegation-evidence


;; FEEDACK: in hoeverre mogen we de XACML spec als betrouwbare
;; documentatie gebruiken? Het lijkt dat het antwoord "helemaal niet"
;; is.
;;
;; https://ishare-3.gitbook.io/ishare-trust-framework-collection/readme/detailed-descriptions/technical/structure-of-delegation-evidence


(defn ->poort8-policy
  [{:keys [date subject consignment-ref]}]
  {:pre [subject date consignment-ref]}
  (let [{:keys [notBefore notOnOrAfter]} (date->not-before-not-on-or-after date)]
    {:subjectId       subject
     :useCase         "iSHARE"
     :serviceProvider nil
     :action          "BDI.PICKUP"
     :resourceId      consignment-ref
     :type            "consignment-ref"
     :attribute       "*"
     :license         license
     :notBefore       notBefore
     ;; FEEDBACK: API should use `notOnOrAfter` instead of `expiration`
     :expiration      notOnOrAfter}))

(defn- mask-target
  ;; FEEDBACK waarom moet ik lege lijst van serviceProviders doorgeven voor masks?

  ;; FEEDBACK by Poort8 AR mag dit ook niet leeg zijn
  [target]
  (assoc-in target [:environment :serviceProviders] ["Dummy"]))

(defn ->delegation-mask
  [{:keys [issuer subject target]}]
  {:pre [issuer subject target]}
  {:delegationRequest
   {:policyIssuer issuer
    :target       {:accessSubject subject}
    :policySets   [{:policies [{:rules  [{:effect "Permit"}]
                                :target (mask-target target)}]}]}})

(defn permit?
  "Test if target is allowed on given Delegation Evidence."
  [delegation-evidence target]
  {:pre [delegation-evidence target]}
  (let [now (local-date-time->epoch (LocalDateTime/now))]
    (and (>= now (:notBefore delegation-evidence))
         (< now (:notOnOrAfter delegation-evidence))
         (= [{:effect "Permit"}]
            (->> delegation-evidence
                 :policySets
                 (mapcat :policies)
                 (filter #(= (:target %) (mask-target target)))
                 (mapcat :rules))))))

(defn rejection-reasons
  "Collect reasons to reject target for delegation-evidence.

  The result is a list of tuples: reason key and the offending value."
  [delegation-evidence target]
  (let [now               (local-date-time->epoch (LocalDateTime/now))
        policies          (->> delegation-evidence
                               :policySets
                               (mapcat :policies))
        matching-policies (filter #(= (:target %) (mask-target target))
                                  policies)
        rules             (mapcat :rules matching-policies)]
    (cond-> []
      (and (seq rules)
           (not= rules [{:effect "Permit"}]))
      (conj (t "policy-rejection-reason/no-permit"
               {:rules  (pr-str rules)}))

      (and (:notBefore delegation-evidence)
           (< now (:notBefore delegation-evidence)))
      (conj (t "policy-rejection-reason/not-before"
               {:tstamp (epoch->str (:notBefore delegation-evidence))}))

      (and (:notOnOrAfter delegation-evidence)
           (>= now (:notOnOrAfter delegation-evidence)))
      (conj (t "policy-rejection-reason/not-on-or-after"
               {:tstamp (epoch->str (:notOnOrAfter delegation-evidence))}))

      (empty? matching-policies)
      (conj (t "policy-rejection-reason/no-matches" {:policies (pr-str policies)}))

      :finally
      (seq))))

(defn outsource-pickup-access-subject
  "Returns an \"accessSubject\" to denote a pickup is outsourced to some party.

  Note: the given trip/transport-order can have either a directly
  assigned `carrier` or, in case of an outsourced trip, a list of
  `carriers` in which case the last is the party to perform the
  pickup."
  [{:keys [ref carrier carriers]}]
  (let [carrier-eori (or (:eori carrier) (-> carriers last :eori))]
    (assert (and ref carrier-eori))
    (str carrier-eori "#ref=" ref)))

(defn pickup-access-subject
  "Returns an \"accessSubject\" to denote a pickup will be done by a driver / vehicle.

  Note: the given trip/transport-order can have either a directly
  assigned `carrier` or, in case of an outsourced trip, a list of
  `carriers` in which case the last is the party to perform the
  pickup."
  [{:keys [carrier carriers driver-id-digits license-plate]}]
  (let [carrier-eori (or (:eori carrier) (-> carriers last :eori))]
    (assert (and carrier-eori driver-id-digits license-plate))
    (str carrier-eori "#driver-id-digits=" driver-id-digits "&license-plate=" license-plate)))


(defn- own-ar-request
  "Set base-url and server-id from authorization-registry keys if not already set."
  [{:ishare/keys [authorization-registry-id
                  authorization-registry-base-url
                  base-url
                  server-id]
    :as          request}]
  (if (and base-url server-id)
    request
    (assoc request
           :ishare/base-url  authorization-registry-base-url
           :ishare/server-id authorization-registry-id)))

(defn ishare-policy-request
  [request delegation-evidence]
  (-> request
      (own-ar-request)
      (assoc :ishare/operation    :ishare/policy
             :method              :post
             :path                "policy"
             :as                  :json
             :json-params         delegation-evidence
             :ishare/unsign-token "policy_token"
             :ishare/lens         [:body "policy_token"])))

(defn poort8-policy-request
  [request params]
  (-> request
      (own-ar-request)
      (assoc :ishare/operation :poort8/policy
             :method :post
             :path "../policies"
             :as :json
             :json-params (assoc params
                                 :useCase "iSHARE")
             :ishare/lens [:body])))

(defn poort8-delete-policy-request
  [request policy-id]
  (-> request
      (own-ar-request)
      (assoc :ishare/operation :poort8/delete-policy
             :method :delete
             :path (str "../policies/" policy-id)
             :as :json
             :ishare/lens [:body])))
