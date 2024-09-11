;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.erp.web
  (:require [clojure.string :as string]
            [compojure.core :refer [DELETE GET POST routes]]
            [dil-demo.events :as events]
            [dil-demo.master-data :as d]
            [dil-demo.otm :as otm]
            [dil-demo.store :as store]
            [dil-demo.web-form :as f]
            [dil-demo.web-utils :as w]
            [ring.util.response :refer [redirect]])
  (:import (java.time LocalDateTime)
           (java.util Date UUID)))

(defn list-consignments [consignments {:keys [eori->name]}]
  [:main
   [:section.actions
    [:a.button.primary {:href "consignment-new"} "Nieuwe order aanmaken"]]

   (when-not (seq consignments)
     [:article.empty
      [:p "Nog geen klantorders geregistreerd.."]])

   (for [{:keys [id ref goods load unload carrier status]} consignments]
     [:article
      [:header
       [:div.status (otm/status-titles status)]
       [:div.ref-date ref " / " (:date load)]
       [:div.from-to (-> load :location-eori eori->name) " → " (:location-name unload)]]

      [:div.goods goods]
      [:div.carrier (-> carrier :eori eori->name)]

      [:footer.actions
       (when (= otm/status-draft status)
         [:a.button.primary {:href  (str "consignment-" id)
                             :title "Eigenschappen aanpassen"}
          "Aanpassen"])
       (when (= otm/status-draft status)
         [:a.button.secondary {:href  (str "publish-" id)
                               :title "Opdracht versturen naar locatie en vervoerder"}
          "Versturen"])
       (f/delete-button (str "consignment-" id))]])

   [:nav.bottom
    "Zie ook:"
    [:ul
     [:li [:a {:href "pulses/"} "Pulses"]]]]])

(defn edit-consignment [consignment {:keys [carriers warehouses]}]
  (f/form consignment {:method "POST"}
    [:section
     (f/select :status {:label "Status", :list otm/status-titles, :required true})
     (f/number :ref {:label "Klantorder nr.", :required true})]

    [:section
     (f/date [:load :date] {:label "Ophaaldatum", :required true})
     (f/select [:load :location-eori] {:label "Ophaaladres", :list warehouses, :required true})
     (f/textarea [:load :remarks] {:label "Opmerkingen"})]

    [:section
     (f/date [:unload :date] {:label "Afleverdatum", :required true})
     (f/text [:unload :location-name] {:label "Afleveradres", :list (keys d/locations), :required true})
     (f/textarea [:unload :remarks] {:label "Opmerkingen"})]

    [:section
     (f/text :goods {:label "Goederen", :list d/goods, :required true})
     (f/select [:carrier :eori] {:label "Vervoerder" :list (into {nil nil} carriers), :required true})]

    (f/submit-cancel-buttons)))

(defn deleted-consignment [{:keys [explanation]}]
  [:div
   [:section
    [:div.actions
     [:a.button {:href "."} "Terug naar overzicht"]]]
   (w/explanation explanation)])

(defn publish-consignment [consignment {:keys [eori->name warehouse-addresses]}]
  (let [{:keys [status ref load unload goods carrier]} consignment]
    (f/form consignment {:method "POST"}
      (when (not= otm/status-draft status)
        [:div.flash.flash-warning
         "Let op!  Deze opdracht is al verzonden!"])

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
         [:dd (:date unload)]]
        [:div
         [:dt "Vervoerder"]
         [:dd (-> carrier :eori eori->name)]]]]
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
          [:blockquote.remarks (:remarks unload)])]]
      [:section.goods
       [:fieldset
        [:legend "Goederen"]
        [:pre goods]]]

      (f/submit-cancel-buttons {:submit {:label "Versturen"
                                         :onclick "return confirm('Zeker weten?')"}}))))

(defn published-consignment [consignment
                             {:keys [eori->name]}
                             {:keys [explanation]}]
  [:div
   [:section
    [:p "Transportopdracht verstuurd naar locatie "
     [:q (-> consignment :load :location-eori eori->name)]
     " en vervoerder "
     [:q (-> consignment :carrier :eori eori->name)]
     "."]
    [:div.actions
     [:a.button {:href "."} "Terug naar overzicht"]]]
   (w/explanation explanation)])



(defn get-consignments [store]
  (->> store :consignments vals (sort-by :ref) (reverse)))

(defn get-consignment [store id]
  (get-in store [:consignments id]))

(defn get-consignment-by-ref [store ref]
  (->> store
      :consignments
      vals
      (some #(when (= (:ref %) ref)
               %))))

(defn min-ref [user-number]
  (let [dt (LocalDateTime/now)]
    (loop [result  0
           factors [[5 user-number]
                    [3 (.getYear dt)]
                    [365 (.getDayOfYear dt)]
                    [24 (.getHour dt)]
                    [60 (.getMinute dt)]
                    [60 (.getSecond dt)]]]
      (if-let [[[scale amount] _] factors]
        (recur (+ (* scale result) (mod amount scale))
               (next factors))
        result))))

(defn next-consignment-ref [store user-number]
  (let [refs (->> store
                  (get-consignments)
                  (map :ref)
                  (map parse-long))]
    (str (inc (apply max (min-ref user-number) refs)))))



(defn consignment->subscription [{:keys [ref], {:keys [eori]} :owner}
                                 user-number
                                 site-id]
  {:topic       ref
   :owner-eori  eori
   :user-number user-number
   :site-id     site-id})

(defn make-handler [{:keys [eori site-id site-name]}]
  {:pre [(keyword? site-id) site-name]}
  (let [slug     (name site-id)
        render   (fn render [title main flash & {:keys [slug-postfix]}]
                   (w/render (str slug slug-postfix)
                             main
                             :flash flash
                             :title title
                             :site-name site-name))
        params-> (fn params-> [params]
                   (-> params
                       (select-keys [:id :status :ref :load :unload :goods :carrier])
                       (assoc-in [:owner :eori] eori)))]
    (routes
     (GET "/" {:keys [flash master-data ::store/store]}
       (render "Orders"
               (list-consignments (get-consignments store) master-data)
               flash))

     (GET "/consignment-new" {:keys [flash master-data ::store/store user-number]}
       (render "Order aanmaken"
               (edit-consignment
                {:ref    (next-consignment-ref store user-number)
                 :load   {:date (w/format-date (Date.))}
                 :unload {:date (w/format-date (Date.))}
                 :status otm/status-draft}
                master-data)
               flash))

     (POST "/consignment-new" {:keys [params]}
       (let [consignment (-> params
                             (params->)
                             (assoc :id (str (UUID/randomUUID))))]
         (-> "."
             (redirect :see-other)
             (assoc :flash {:success (str "Order " (:ref consignment) " aangemaakt")})
             (assoc ::store/commands [[:put! :consignments consignment]]))))

     (GET "/consignment-:id" {:keys        [flash master-data ::store/store]
                              {:keys [id]} :params}
       (when-let [consignment (get-consignment store id)]
         (render (str "Order " (:ref consignment) " aanpassen")
                 (edit-consignment consignment master-data)
                 flash)))

     (POST "/consignment-:id" {:keys [params]}
       (let [consignment (params-> params)]
         (-> "."
             (redirect :see-other)
             (assoc :flash {:success (str "Order " (:ref consignment) " aangepast")})
             (assoc ::store/commands [[:put! :consignments consignment]]))))

     (DELETE "/consignment-:id" {:keys        [::store/store user-number]
                                 {:keys [id]} :params}
       (when-let [consignment (get-consignment store id)]
         (-> "deleted"
             (redirect :see-other)
             (assoc :flash {:success (str "Order " (:ref consignment) " verwijderd")}
                    ::store/commands [[:delete! :consignments id]]
                    ::events/commands [[:unsubscribe!
                                        (consignment->subscription consignment
                                                                   user-number
                                                                   site-id)]]))))

     (GET "/deleted" {:keys [flash]}
       (render "Order verwijderd"
               (deleted-consignment flash)
               flash))

     (GET "/publish-:id" {:keys        [flash master-data ::store/store]
                          {:keys [id]} :params}
       (when-let [consignment (get-consignment store id)]
         (render (str "Order " (:ref consignment) " naar locatie en vervoerder sturen")
                 (publish-consignment consignment master-data)
                 flash)))

     (POST "/publish-:id" {:keys        [master-data ::store/store
                                         user-number]
                           {:keys [id]} :params}
       (when-let [consignment (get-consignment store id)]
         (let [consignment     (assoc consignment :status otm/status-requested)
               ref             (:ref consignment)
               transport-order (otm/consignment->transport-order consignment)
               trip            (otm/consignment->trip consignment)
               warehouse-eori  (-> consignment :load :location-eori)
               carrier-eori    (-> consignment :carrier :eori)]
           (-> (str "published-" id)
               (redirect :see-other)
               (assoc :flash {:success     (str "Order " ref " verstuurd")
                              :explanation [["Stuur OTM Transportopdracht naar WMS van DC"
                                             {:otm-object (otm/->transport-order transport-order master-data)}]
                                            ["Stuur OTM Trip naar TMS van Vervoerder"
                                             {:otm-object (otm/->trip trip master-data)}]]}

                      ::store/commands [[:put! :consignments consignment]
                                        [:publish! :transport-orders
                                         warehouse-eori transport-order]
                                        [:publish! ;; to carrier TMS
                                         :trips
                                         carrier-eori trip]]

                      ;; warehouse is the only party creating events currently
                      ::events/commands [[:authorize!
                                          {:topic       ref
                                           :owner-eori  eori
                                           :read-eoris  [eori carrier-eori]
                                           :write-eoris [warehouse-eori]}]
                                         [:subscribe!
                                          (consignment->subscription consignment
                                                                     user-number
                                                                     site-id)]])))))

     (GET "/published-:id" {:keys        [flash master-data ::store/store]
                            {:keys [id]} :params}
       (when-let [consignment (get-consignment store id)]
         (render (str "Order "
                      (:ref consignment)
                      " verstuurd")
                 (published-consignment consignment master-data flash)
                 flash))))))
