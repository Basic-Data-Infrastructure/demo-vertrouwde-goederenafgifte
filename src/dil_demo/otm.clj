;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.otm
  (:require [clojure.spec.alpha :as s])
  (:import java.util.UUID))

;; standard OTM

(def status-draft "draft")
(def status-requested "requested")
(def status-confirmed "confirmed")
(def status-in-transit "inTransit")
(def status-completed "completed")
(def status-cancelled "cancelled")
(def status-assigned "assigned") ;; custom
(def status-outsourced "outsourced") ;; custom

(def status-titles
  {status-draft      "Klad"
   status-requested  "Ingediend"
   status-confirmed  "Bevestigd"
   status-in-transit "In Transit"
   status-completed  "Afgerond"
   status-cancelled  "Geannuleerd"

   ;; custom
   status-assigned   "Toegewezen"
   status-outsourced "Uitbesteed"})

(def role-owner "owner")
(def role-carrier "carrier")
(def role-subcontractor "subcontractor")
(def role-driver "driver")

(def action-type-load "load")
(def action-type-unload "unload")

(def association-type-inline "inline")

(def location-type-warehouse "warehouse")

(def contact-details-type-eori "eori")



;; internal representation specs

(s/def ::status #{status-draft
                  status-requested
                  status-confirmed
                  status-in-transit
                  status-completed
                  status-cancelled
                  status-assigned
                  status-outsourced})

(s/def ::eori
  #(re-matches #"..\.EORI\..*" %))

(s/def ::actor
  (s/keys :req-un [::eori]))

(s/def ::owner ::actor)
(s/def ::carrier ::actor)

(s/def ::date
  (s/and string?
         #(re-matches #"\d{4}-\d{2}-\d{2}" %)))

(s/def ::action
  (s/keys :req-un [::date
                   ::location]
          :opt-un [::remarks]))

(s/def ::load ::action)
(s/def ::unload ::action)

(s/def ::consignment
  (s/keys :req-un [::id
                   ::ref
                   ::status
                   ::goods
                   ::load
                   ::unload
                   ::carrier
                   ::owner]))

(s/def ::carriers
  (s/coll-of ::carrier :kind vector?))

(s/def ::trip
  (s/keys :req-un [::id
                   ::ref
                   ::status
                   ::carriers ;; list of carrier and subcontrators
                   ::load
                   ::unload]
          :opt-un [::driver-id-digits
                   ::license-plate]))

(s/def ::transport-order
  (s/keys :req-un [::id
                   ::ref
                   ::owner
                   ::carrier
                   ::load
                   ::goods]))

(defn check! [type value]
  (when-let [spec ({:consignments     ::consignment
                    :trips            ::trip
                    :transport-orders ::transport-order}
                   type)]
    (when-let [data (s/explain-data spec value)]
      (throw (ex-info (s/explain-str spec value)
                      {:spec    spec
                       :value   value
                       :explain data})))))


;; conversion to other types

(defn consignment->transport-order [consignment]
  (-> consignment
      (select-keys [:ref :status :owner :carrier :load :goods])
      (assoc :id (str (UUID/randomUUID)))))

(defn consignment->trip [{:keys [carrier] :as consignment}]
  (-> consignment
      (select-keys [:ref :status :owner :load :unload])
      (assoc :carriers [carrier])
      (assoc :id (str (UUID/randomUUID)))))



;; conversion to OTM

(defn ->actor [{:keys [role eori external-attributes]}]
  (cond->
      {:association-type association-type-inline
       :roles            role
       :entity           {:contact-details [{:type  contact-details-type-eori
                                             :value eori}]}}
    external-attributes
    (assoc-in [:entity :external-attributes] external-attributes)))

(defn ->action [{:keys [action-type date location remarks]}]
  (cond->
      {:association-type association-type-inline
       :entity
       {:action-type action-type
        :start-time  date
        :location    {:association-type association-type-inline
                      :entity           {:name          location
                                         :type          location-type-warehouse
                                         :geo-reference {}}}}}
    remarks (assoc-in [:entity :remarks] remarks)))

(defn ->vehicle [{:keys [license-plate]}]
  {:association-type association-type-inline
   :entity           {:license-plate license-plate}})

(defn ->goods [{:keys [goods]}]
  {:association-type association-type-inline
   :entity           {:goods goods}})

(defn ->consignment
  "Convert internal representation to OTM consignment."
  [{:keys [id ref status goods owner carrier load-action unload-action]}]
  {:id                  id
   :external-attributes {:ref ref}
   :status              status

   :goods [(->goods {:goods goods})]

   :actors
   [(->actor (assoc carrier :role role-carrier))
    (->actor (assoc owner :role role-owner))]

   :actions
   [(->action (assoc load-action :action-type action-type-load))
    (->action (assoc unload-action :action-type action-type-unload))]})

(defn ->trip
  "Convert internal representation to OTM trip."
  [{:keys [id ref status carriers load unload driver-id-digits license-plate]}]
  (cond->
      {:id                  id
       :external-attributes {:consignment-ref ref}
       :status              status

       :actors
       ;; first carriers is the "carrier" the rest are "subcontrators"
       (concat [(->actor (-> carriers (first) (assoc :role role-carrier)))]
               (->> carriers
                    (drop 1)
                    (map #(->actor (assoc % :role role-subcontractor)))))

       :actions
       [(->action (assoc load :action-type action-type-load))
        (->action (assoc unload :action-type action-type-unload))]}

    driver-id-digits
    (update :actors conj
            (->actor {:role role-driver
                      :external-attributes {:id-digits driver-id-digits}}))

    license-plate
    (assoc :vehicle
           [(->vehicle license-plate)])))

(defn ->transport-order
  "Convert internal representation to OTM transport order."
  [{:keys [id ref owner carrier load goods]}]
  {:id                  id
   :consigments
   [{:association-type association-type-inline
     :entity {:external-attributes {:ref ref}

              :goods [(->goods {:goods goods})]

              :actors
              [(->actor (assoc carrier :role role-carrier))
               (->actor (assoc owner :role role-owner))]

              :actions
              ;; only loading information is relevant in WMS
              [(->action (assoc load :action-type action-type-load))]}}]})
