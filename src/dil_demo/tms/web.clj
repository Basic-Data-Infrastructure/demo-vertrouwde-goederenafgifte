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
            [dil-demo.master-data :as d]
            [dil-demo.otm :as otm]
            [dil-demo.store :as store]
            [dil-demo.web-form :as f]
            [dil-demo.web-utils :as w]
            [ring.util.response :refer [redirect]]))

(defn list-trips [trips {:keys [eori->name]}]
  [:main
   (when-not (seq trips)
     [:article.empty
      [:p "Nog geen transportopdrachten geregistreerd.."]])

   (for [{:keys [id ref status load unload driver-id-digits license-plate carriers]} trips]
     [:article
      [:header
       [:div.status (otm/status-titles status)]
       [:div.ref-date ref " / " (:date load)]
       [:div.from-to
        (-> load :location-eori eori->name)
        " → "
        (:location-name unload)]]
      (cond
        (and driver-id-digits license-plate)
        [:p.assigned
         "Toegewezen aan "
         [:q.driver-id-digits (w/or-em-dash driver-id-digits)]
         " / "
         [:q.license-plate (w/or-em-dash license-plate)]]

        (and (= otm/status-outsourced status) (-> carriers last :eori))
        [:p.outsourced
         "Uitbesteed aan "
         [:q (-> carriers last :eori eori->name)]]

        :else
        [:em "Nog niet toegewezen.."])
      [:footer.actions
       (when (= otm/status-requested status)
         [:a.button.primary {:href  (str "assign-" id)
                             :title "Toewijzen aan een chauffeur en wagen"}
          "Toewijzen"])
       (when (= otm/status-requested status)
         [:a.button.secondary {:href  (str "outsource-" id)
                               :title "Uitbesteden aan een andere vervoerder"}
          "Uitbesteden"])
       (f/delete-button (str "trip-" id))]])

   [:nav.bottom
    "Zie ook:"
    [:ul
     [:li [:a {:href "chauffeur/"} "Chauffeur"]]
     [:li [:a {:href "pulses/"} "Pulses"]]]]])

(defn qr-code-dil-demo [{:keys [carriers driver-id-digits license-plate]}]
  (w/qr-code (str ":dil-demo"
                  ":" (->> carriers (map :eori) (string/join ","))
                  ":" driver-id-digits
                  ":" license-plate)))

(defn chauffeur-list-trips [trips {:keys [eori->name]}]
  [:main
   (when-not (seq trips)
     [:article.empty
      [:p "Nog geen transportopdrachten geregistreerd.."]])

   (for [{:keys [id ref load unload]} trips]
     [:article
      [:header
       [:div.ref-date ref " / " (:date load)]]
      [:div.from-to
       (-> load :location-eori eori->name)
       " → "
       (:location-name unload)]
      [:footer.actions
       [:a.button.primary {:href (str "trip-" id)} "Tonen"]]])])

(defn chauffeur-trip [trip]
  [:div.trip
   [:section
    (qr-code-dil-demo trip)]

   (f/form (assoc trip
                  :eoris (->> trip :carriers (map :eori) (string/join ",")))
       {}
     [:fieldset
      (f/text :eoris {:label "Vervoerder EORI's", :readonly true})
      (f/text :driver-id-digits {:label "Rijbewijs", :readonly true})
      (f/text :license-plate {:label "Kenteken", :readonly true})]

     [:div.actions
      (f/cancel-button {:href "../chauffeur/"
                        :label "Terug naar overzicht"})])])

(defn trip-details [{:keys [ref load unload]}
                    {:keys [eori->name warehouse-addresses]}]
  [:section
   [:section.details
    [:dl
     [:div
      [:dt "Klantorder nr."]
      [:dd ref]]
     [:div
      [:dt "Ophaaldatum"]
      [:dd (:date load)]]
     [:div
      [:dt "Afleverdatum"]
      [:dd (:date unload)]]]]

   [:section.trip
    [:fieldset.load-location
     [:legend "Ophaaladres"]
     [:h3 (-> load :location-eori eori->name)]
     (when-let [address (-> load :location-eori warehouse-addresses)]
       [:pre address])
     (when-not (string/blank? (:remarks load))
       [:blockquote.remarks (:remarks load)])]
    [:fieldset.unload-location
     [:legend "Afleveradres"]
     [:h3 (:location-name unload)]
     (when-let [address (-> unload :location-name d/locations)]
       [:pre address])
     (when-not (string/blank? (:remarks unload))
       [:blockquote.remarks (:remarks unload)])]]])

(defn assign-trip [{:keys [driver-id-digits license-plate] :as trip}
                   master-data]
  (f/form trip {:method "POST"}
    (when (and driver-id-digits license-plate)
      (qr-code-dil-demo trip))

    (trip-details trip master-data)

    (f/text :driver-id-digits {:label "Rijbewijs", :placeholder "Laatste 4 cijfers", :pattern "\\d{4}", :required true})
    (f/text :license-plate {:label "Kenteken", :required true})

    (f/submit-cancel-buttons {:submit {:label "Toewijzen"}})))

(defn assigned-trip [{:keys [ref] :as trip} {:keys [explanation]}]
  [:div
   [:section
    [:p "Opdracht " [:q ref] " toegewezen."]

    (qr-code-dil-demo trip)

    [:div.actions
     [:a.button {:href "."} "Terug naar overzicht"]]]
   (w/explanation explanation)])

(defn outsource-trip [trip {:keys [carriers] :as master-data}]
  (f/form trip {:method "POST"}
    (trip-details trip master-data)

    [:section
     (f/select [:carrier :eori] {:label "Vervoerder", :list carriers, :required true})]

    (f/submit-cancel-buttons {:submit {:label   "Uitbesteden"
                                       :onclick "return confirm('Zeker weten?')"}})))

(defn outsourced-trip [{:keys [ref carriers]}
                       {:keys [explanation]}
                       master-data]
  [:div
   [:section
    [:p
     "Opdracht " [:q ref]
     " uitbesteed aan " [:q (get (:carriers master-data)
                                 (-> carriers last :eori))]]

    [:div.actions
     [:a.button {:href "."} "Terug naar overzicht"]]]
   (w/explanation explanation)])

(defn deleted-trip [{:keys [explanation]}]
  [:div
   [:section
    [:div.actions
     [:a.button {:href "."} "Terug naar overzicht"]]]
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
        render (fn render [title main flash & {:keys [slug-postfix]}]
                 (w/render (str slug slug-postfix)
                           main
                           :flash flash
                           :title title
                           :site-name site-name))]
    (routes
     (GET "/" {:keys [flash master-data ::store/store]}
       (render "Opdrachten"
               (list-trips (get-trips store) master-data)
               flash))

     (GET "/chauffeur/" {:keys [flash master-data ::store/store]}
       (render "Opdrachten"
               (chauffeur-list-trips (filter #(= otm/status-assigned (:status %))
                                             (get-trips store))
                                     master-data)
               flash
               :slug-postfix "-chauffeur"))

     (GET "/chauffeur/trip-:id" {:keys        [flash ::store/store]
                                 {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (when (= otm/status-assigned (:status trip))
           (render (:ref trip)
                   (chauffeur-trip trip)
                   flash
                   :slug-postfix "-chauffeur"))))

     (DELETE "/trip-:id" {::store/keys [store]
                          :keys        [user-number]
                          {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (-> "deleted"
             (redirect :see-other)
             (assoc :flash {:success "Opdracht verwijderd"}
                    ::store/commands [[:delete! :trips id]]
                    ::events/commands [[:unsubscribe! (trip->subscription trip
                                                                          user-number
                                                                          site-id)]]))))

     (GET "/deleted" {:keys [flash]}
       (render "Opdracht verwijderd"
               (deleted-trip flash)
               flash))

     (GET "/assign-:id" {:keys        [flash master-data]
                         ::store/keys [store]
                         {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (render (str "Opdracht " (:ref trip) " toewijzen")
                 (assign-trip trip master-data)
                 flash)))

     (POST "/assign-:id" {::store/keys            [store]
                          :keys                   [user-number]
                          {:keys [id
                                  driver-id-digits
                                  license-plate]} :params}
       (when-let [{:keys [ref] :as trip} (get-trip store id)]
         (let [trip (-> trip
                        (assoc :status otm/status-assigned
                               :driver-id-digits driver-id-digits
                               :license-plate license-plate))]
           (-> (str "assigned-" id)
               (redirect :see-other)
               (assoc :flash {:success (str "Chauffeur en kenteken toegewezen aan opdracht " ref)}
                      ::store/commands [[:put! :trips trip]]
                      ::events/commands [[:subscribe! (trip->subscription trip
                                                                          user-number
                                                                          site-id)]])))))

     (GET "/assigned-:id" {:keys        [flash]
                           ::store/keys [store]
                           {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (render (str "Opdracht " (:ref trip) " toegewezen")
                 (assigned-trip trip flash)
                 flash)))

     (GET "/outsource-:id" {:keys        [flash master-data ::store/store]
                            {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (render (str "Opdracht " (:ref trip) " uitbesteden aan een andere vervoerder")
                 (outsource-trip trip (-> master-data
                                          ;; can't outsource to ourselves
                                          (update :carriers dissoc own-eori)))
                 flash)))

     (POST "/outsource-:id" {:keys                [master-data ::store/store user-number]
                             {:keys [id carrier]} :params}
       (when-let [trip (get-trip store id)]
         (let [trip (update trip :carriers conj carrier)]
           (-> (str "outsourced-" id)
               (redirect :see-other)
               (assoc :flash {:success     (str "Opdracht " (:ref trip) " naar vervoerder gestuurd")
                              :explanation [["Stuur OTM Trip naar TMS van andere vervoerder"
                                             {:otm-object (otm/->trip trip master-data)}]]}
                      ::store/commands [[:put! :trips (assoc trip :status otm/status-outsourced)]
                                        [:publish! :trips (:eori carrier) trip]]
                      ::events/commands [[:subscribe! (trip->subscription trip
                                                                          user-number
                                                                          site-id)]])))))

     (GET "/outsourced-:id" {:keys        [flash master-data ::store/store]
                             {:keys [id]} :params}
       (when-let [trip (get-trip store id)]
         (render (str "Opdracht " (:ref trip) " uitbesteed")
                 (outsourced-trip trip flash master-data)
                 flash))))))
