;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.tms.web
  (:require [clojure.string :as string]
            [compojure.core :refer [DELETE GET POST routes]]
            [dil-demo.events :as events]
            [dil-demo.i18n :refer [t]]
            [dil-demo.master-data :as d]
            [dil-demo.otm :as otm]
            [dil-demo.store :as store]
            [dil-demo.web-form :as f]
            [dil-demo.web-utils :as w]
            [ring.util.response :refer [redirect]]))

(defn list-trips [trips {:keys [eori->name]}]
  [:main
   [:div.table-list-wrapper
    [:table.list.trips
     [:thead
      [:th.ref (t "label/ref")]
      [:th.load-location (t "label/load-location")]
      [:th.load-date (t "label/load-date")]
      [:th.unload-location (t "label/unload-location")]
      [:th.unload-date (t "label/unload-date")]
      [:th.license-plate (t "label/license-plate")]
      [:th.driver-id-digits (t "label/driver-id-digits")]
      [:th.status (t "label/status")]
      [:th.actions]]
     [:tbody
      (when-not (seq trips)
        [:tr.empty [:td {:colspan 999} (t "empty")]])

      (for [{:keys [id ref load unload license-plate driver-id-digits status carriers]} trips]
        [:tr
         [:td.ref [:span ref]]
         [:td.load-location [:span (-> load :location-eori eori->name)]]
         [:td.load-date [:span (:date load)]]
         [:td.unload-location [:span (-> unload :location-name)]]
         [:td.unload-date [:span (:date unload)]]
         (if (= otm/status-outsourced status)
           [:td.outsourced {:colspan 2}
            [:span (-> carriers last :eori eori->name)]]
           [:div
            [:td.license-plate (if license-plate
                                 [:span license-plate]
                                 [:span "—"])]
            [:td.driver-id-digits (if driver-id-digits
                                    [:span driver-id-digits]
                                    [:span.empty "—"])]])
         [:td.status (w/status-span status)]
         [:td.actions
          [:div.actions-wrapper
           (when (= otm/status-requested status)
             [:a.button.primary.assign
              {:href      (str "assign-" id)
               :title     (t "tms/tooltip/assign")
               :fx-dialog "#modal-dialog"}
              (t "tms/button/assign")])

           (when (= otm/status-requested status)
             [:a.button.primary.outsource
              {:href      (str "outsource-" id)
               :title     (t "tms/tooltip/outsource")
               :fx-dialog "#modal-dialog"}
              (t "tms/button/outsource")])]

          (when (not= otm/status-requested status)
            (f/delete-button (str "trip-" id)
                             {:form {:fx-dialog "#modal-dialog"}}))]])]]]])

(defn qr-code-dil-demo [{:keys [carriers driver-id-digits license-plate]}]
  (w/qr-code (str ":dil-demo"
                  ":" (->> carriers (map :eori) (string/join ","))
                  ":" driver-id-digits
                  ":" license-plate)))

(defn chauffeur-list-trips [trips {:keys [eori->name]}]
  [:main
   [:div.table-list-wrapper
    [:table.list.trips
     [:thead
      [:th.ref (t "label/ref")]
      [:th.load-location (t "label/load-location")]
      [:th.load-date (t "label/load-date")]
      [:th.unload-location (t "label/unload-location")]
      [:th.unload-date (t "label/unload-date")]]
     [:tbody
      (when-not (seq trips)
        [:tr.empty [:td {:colspan 999} (t "empty")]])

      (for [{:keys [id ref load unload]} trips]
        [:tr.fx-clickable
         [:td.ref
          [:a {:href (str "trip-" id), :fx-onclick-tr true} ref]]
         [:td.load-location [:span (-> load :location-eori eori->name)]]
         [:td.load-date [:span (:date load)]]
         [:td.unload-location [:span (-> unload :location-name)]]
         [:td.unload-date [:span (:date unload)]]])]]]])

(defn chauffeur-trip [trip]
  [:div.trip
   [:section
    (qr-code-dil-demo trip)]

   (f/form (assoc trip
                  :eoris (->> trip :carriers (map :eori) (string/join ",")))
       {}
     [:fieldset
      (f/text :eoris {:label (t "label/carrier-eories"), :readonly true})
      (f/text :driver-id-digits {:label (t "label/driver-id-digits"), :readonly true})
      (f/text :license-plate {:label (t "label/license-plate"), :readonly true})]

     [:div.actions
      (f/cancel-button {:href "../chauffeur/"
                        :label (t "button/list")})])])

(defn trip-details [{:keys [ref load unload]}
                    {:keys [eori->name warehouse-addresses]}]
  [:div
   [:fieldset.primary
    [:dl
     [:div
      [:dt (t "label/ref")]
      [:dd ref]]
     [:div
      [:dt (t "label/load-date")]
      [:dd (:date load)]]
     [:div
      [:dt (t "label/unload-date")]
      [:dd (:date unload)]]]]

   [:section.trip
    [:fieldset.load-location
     [:legend (t "label/load-location")]
     [:h3 (-> load :location-eori eori->name)]
     (when-let [address (-> load :location-eori warehouse-addresses)]
       [:p.address address])
     (when-not (string/blank? (:remarks load))
       [:blockquote.remarks (:remarks load)])]
    [:fieldset.unload-location
     [:legend (t "label/unload-location")]
     [:h3 (:location-name unload)]
     (when-let [address (-> unload :location-name d/locations)]
       [:p.address address])
     (when-not (string/blank? (:remarks unload))
       [:blockquote.remarks (:remarks unload)])]]])

(defn assign-trip [{:keys [driver-id-digits license-plate] :as trip}
                   master-data]
  (f/form trip {:method    "POST"
                :fx-dialog "#modal-dialog"}
    (when (and driver-id-digits license-plate)
      (qr-code-dil-demo trip))

    (trip-details trip master-data)

    [:fieldset
     [:legend (t "tms/button/assign")]
     [:fieldpair
      (f/text :driver-id-digits {:label       (t "label/driver-id-digits")
                                 :placeholder (t "tms/placeholder/driver-id-digits")
                                 :pattern     "\\d{4}", :required true})
      (f/text :license-plate {:label    (t "label/license-plate")
                              :required true})]]

    (f/submit-cancel-buttons {:submit {:label (t "tms/button/assign")
                                       :class "assign"}})))

(defn assigned-trip [trip {:keys [explanation]}]
  [:div
   [:section.primary
    [:p (t "tms/assigned-trip" trip)]]

   (w/explanation explanation)])

(defn outsource-trip [trip {:keys [carriers] :as master-data}]
  (f/form trip {:method "POST"
                :fx-dialog "#modal-dialog"}
    (trip-details trip master-data)

    [:fieldset
     [:legend (t "tms/button/outsource")]

     (f/select [:carrier :eori] {:label (t "label/carrier")
                                 :list carriers, :required true})]

    (f/submit-cancel-buttons {:submit {:label   (t "tms/button/outsource")
                                       :class   "outsource"
                                       :onclick (f/confirm-js)}})))

(defn outsourced-trip [{:keys [ref carriers]}
                       {:keys [explanation]}
                       master-data]
  [:div
   [:section.primary
    [:p
     (t "tms/outsourced-trip" {:ref     ref
                               :carrier (get (:carriers master-data)
                                             (-> carriers last :eori))})]]
   (w/explanation explanation)])

(defn deleted-trip [trip {:keys [explanation]}]
  [:div
   [:section.primary
    [:p (t "tms/deleted-trip" trip)]]
   (w/explanation explanation)])



(defn get-trips [store]
  (->> store :trips vals (sort-by :ref) (reverse)))

(defn get-trip [store id]
  (get-in store [:trips id]))

(defn get-trip-by-ref [store ref]
  (->> store
       :trips
       vals
       (some #(when (= (:ref %) ref)
                %))))



(def ^:dynamic *slug* nil)

(defn trip->subscription [{:keys [ref], {:keys [eori]} :owner} user-number site-id]
  ;; TODO put user-number in trip
  {:topic       ref
   :owner-eori  eori
   :user-number user-number
   :site-id     site-id})

(defn make-handler [{:keys [site-id site-name], own-eori :eori}]
  (let [slug   (name site-id)
        render (fn render [title main flash & {:keys [chauffeur html-class]}]
                 (w/render (str slug (when chauffeur "-chauffeur"))
                           main
                           :flash flash
                           :title title
                           :app-name "tms"
                           :site-name site-name
                           :html-class html-class
                           :navigation (if chauffeur
                                         {:current :contacts
                                          :paths   {:list     ".."
                                                    :contacts "."
                                                    :pulses   "../pulses/"}}
                                         {:current :list
                                          :paths   {:list     "."
                                                    :contacts "chauffeur/"
                                                    :pulses   "pulses/"}})))]
    (routes
     (GET "/" {:keys [flash master-data ::store/store]}
       (render (t "tms/title/list")
               (list-trips (get-trips store) master-data)
               flash
               :html-class "list"))

     (GET "/chauffeur/" {:keys [flash master-data ::store/store]}
       (render (t "tms/title/list")
               (chauffeur-list-trips (filter #(= otm/status-assigned (:status %))
                                             (get-trips store))
                                     master-data)
               flash
               :chauffeur true
               :html-class "list"))

     (GET "/chauffeur/trip-:id" {:keys        [flash ::store/store]
                                 {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (when (= otm/status-assigned (:status trip))
           (render (:ref trip)
                   (chauffeur-trip trip)
                   flash
                   :chauffeur true
                   :html-class "details"))))

     (DELETE "/trip-:id" {::store/keys [store]
                          :keys        [user-number]
                          {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (-> "deleted"
             (redirect :see-other)
             (assoc :flash {:success (t "tms/flash/delete-success")
                            :trip    trip}
                    ::store/commands [[:delete! :trips id]]
                    ::events/commands [[:unsubscribe! (trip->subscription trip
                                                                          user-number
                                                                          site-id)]]))))

     (GET "/deleted" {:keys [flash], {:keys [trip]} :flash}
       (render (t "tms/title/deleted")
               (deleted-trip trip flash)
               flash
               :html-class "delete"))

     (GET "/assign-:id" {:keys        [flash master-data]
                         ::store/keys [store]
                         {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (render (t "tms/title/assign" trip)
                 (assign-trip trip master-data)
                 flash
                 :html-class "assign")))

     (POST "/assign-:id" {::store/keys            [store]
                          :keys                   [user-number]
                          {:keys [id
                                  driver-id-digits
                                  license-plate]} :params}
       (when-let [trip (get-trip store id)]
         (let [trip (-> trip
                        (assoc :status otm/status-assigned
                               :driver-id-digits driver-id-digits
                               :license-plate license-plate))]
           (-> (str "assigned-" id)
               (redirect :see-other)
               (assoc :flash {:success (t "tms/flash/assigned-success" trip)}
                      ::store/commands [[:put! :trips trip]]
                      ::events/commands [[:subscribe! (trip->subscription trip
                                                                          user-number
                                                                          site-id)]])))))

     (GET "/assigned-:id" {:keys        [flash]
                           ::store/keys [store]
                           {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (render (t "tms/title/assigned" trip)
                 (assigned-trip trip flash)
                 flash
                 :html-class "assign")))

     (GET "/outsource-:id" {:keys        [flash master-data ::store/store]
                            {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (render (t "tms/title/outsource" trip)
                 (outsource-trip trip (-> master-data
                                          ;; can't outsource to ourselves
                                          (update :carriers dissoc own-eori)))
                 flash
                 :html-class "outsource")))

     (POST "/outsource-:id" {:keys                [master-data ::store/store user-number]
                             {:keys [id carrier]} :params}
       (when-let [trip (get-trip store id)]
         (let [trip (update trip :carriers conj carrier)]
           (-> (str "outsourced-" id)
               (redirect :see-other)
               (assoc :flash
                      {:success     (t "tms/flash/outsource-success" trip)
                       :explanation [[(t "explanation/tms/outsource")
                                      {:otm-object (otm/->trip trip master-data)}]]}

                      ::store/commands [[:put! :trips (assoc trip :status otm/status-outsourced)]
                                        [:publish! :trips (:eori carrier) trip]]
                      ::events/commands [[:subscribe! (trip->subscription trip
                                                                          user-number
                                                                          site-id)]])))))

     (GET "/outsourced-:id" {:keys        [flash master-data ::store/store]
                             {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (render (t "tms/title/outsourced" trip)
                 (outsourced-trip trip flash master-data)
                 flash
                 :html-class "outsource"))))))
