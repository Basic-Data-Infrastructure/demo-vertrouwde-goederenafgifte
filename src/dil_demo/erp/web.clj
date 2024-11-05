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
            [dil-demo.i18n :refer [t]]
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
    [:a.button.create
     {:href      "consignment-new"
      :fx-dialog "#drawer-dialog"}
     (t "erp/button/new")]]

   [:div.table-list-wrapper
    [:table.list.consignments
     [:thead
      [:th.ref (t "label/ref")]
      [:th.goods (t "label/goods")]
      [:th.load-location (t "label/load-location")]
      [:th.load-date (t "label/load-date")]
      [:th.unload-location (t "label/unload-location")]
      [:th.unload-date (t "label/unload-date")]
      [:th.status (t "label/status")]
      [:th.actions]]
     [:tbody
      (when-not (seq consignments)
        [:tr.empty [:td {:colspan 999} (t "empty")]])

      (for [{:keys [id ref goods load unload carrier status]} consignments]
        [:tr.fx-clickable
         [:td.ref
          [:a {:href          (str "consignment-" id)
               :title         (t "tooltip/edit")
               :fx-dialog     "#drawer-dialog"
               :fx-onclick-tr true}
           ref]]
         [:td.goods [:span goods]]
         [:td.load-location [:span (-> load :location-eori eori->name)]]
         [:td.load-date [:span (:date load)]]
         [:td.unload-location [:span (-> unload :location-name)]]
         [:td.unload-date [:span (:date unload)]]
         [:td.status (w/status-span status)]
         [:td.publish
          (if (= otm/status-draft status)
            [:a.button.primary.publish
             {:href      (str "publish-" id)
              :title     (t "erp/tooltip/publish")
              :fx-dialog "#modal-dialog"}
             (t "erp/button/publish")]
            [:span.carrier (-> carrier :eori eori->name)])]])]]]])

(defn editable? [{:keys [status]}]
  (or (nil? status)
      (= otm/status-draft status)))

(defn edit-consignment [{:keys [id] :as consignment}
                        {:keys [carriers warehouses]}]
  [:div.edit-form
   (f/form consignment {:method "POST"}
     [:fieldset.primary
      [:fieldpair
       (f/number :ref {:label    (t "label/ref")
                       :required true})
       (f/select :status {:label    (t "label/status")
                          :list     (reduce (fn [m status]
                                              (assoc m status
                                                     (t (str "status/" status))))
                                            {}
                                            otm/statuses)
                          :required true})]]

     [:fieldset.secondary
      [:legend (t "label/load")]
      [:fieldpair
       (f/date [:load :date] {:label    (t "label/date")
                              :required true})
       (f/select [:load :location-eori] {:label (t "label/location")
                                         :list  warehouses, :required true})]
      (f/textarea [:load :remarks] {:placeholder (t "placeholder/remarks")})]

     [:fieldset.secondary
      [:legend (t "label/unload")]
      [:fieldpair
       (f/date [:unload :date] {:label (t "label/date"), :required true})
       (f/text [:unload :location-name] {:label (t "label/location")
                                         :list  (keys d/locations), :required true})]
      (f/textarea [:unload :remarks] {:placeholder (t "placeholder/remarks")})]

     [:fieldset.secondary
      [:legend (t "label/goods")]
      (f/text :goods {:label (t "hint/goods"), :list d/goods, :required true})]
     [:fieldset.secondary
      [:legend (t "label/carrier")]
      (f/select [:carrier :eori]
                {:label (t "hint/carrier"), :list (into {nil nil} carriers), :required true})]

     (when (editable? consignment)
       (f/submit-button {:class "secondary"})))

   (when id
     (f/delete-button (str "consignment-" id)
                      {:form {:fx-dialog "#modal-dialog"}}))])

(defn deleted-consignment [consignment {:keys [explanation]}]
  [:div
   [:section.primary
    [:p (t "erp/consignment-deleted" consignment)]]

   (w/explanation explanation)])

(defn publish-consignment [consignment {:keys [eori->name warehouse-addresses]}]
  (let [{:keys [status ref load unload goods carrier]} consignment]
    (f/form consignment {:method    "POST"
                         :fx-dialog "#modal-dialog"}
      (when (not= otm/status-draft status)
        [:div.flash.flash-warning (t "warning/already-published")])

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
         [:dd (:date unload)]]
        [:div
         [:dt (t "label/carrier")]
         [:dd (-> carrier :eori eori->name)]]
        [:div
         [:dt (t "label/goods")]
         [:dd goods]]]]
      [:section.trip
       [:fieldset.location.load
        [:legend (t "label/load-location")]
        [:h3 (-> load :location-eori eori->name)]
        (when-let [address (-> load :location-eori warehouse-addresses)]
          [:p.address address])
        (when-not (string/blank? (:remarks load))
          [:blockquote.remarks (:remarks load)])]
       [:fieldset.location.unload
        [:legend (t "label/unload-location")]
        [:h3 (:location-name unload)]
        (when-let [address (-> unload :location-name d/locations)]
          [:p.address address])
        (when-not (string/blank? (:remarks unload))
          [:blockquote.remarks (:remarks unload)])]]

      (f/submit-cancel-buttons {:submit {:label   (t "erp/button/publish")
                                         :class   "primary publish"
                                         :onclick (f/confirm-js)}}))))

(defn published-consignment [consignment
                             {:keys [eori->name]}
                             {:keys [explanation]}]
  [:div
   [:section.primary
    [:p
     (t "erp/published" {:location (-> consignment :load :location-eori eori->name)
                         :carrier  (-> consignment :carrier :eori eori->name)})]]

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
        render   (fn render [title main flash & {:keys [slug-postfix html-class]}]
                   (w/render (str slug slug-postfix)
                             main
                             :flash flash
                             :title title
                             :html-class html-class
                             :site-name site-name))
        params-> (fn params-> [params]
                   (-> params
                       (select-keys [:id :status :ref :load :unload :goods :carrier])
                       (assoc-in [:owner :eori] eori)))]
    (routes
     (GET "/" {:keys [flash master-data ::store/store]}
       (render (t "erp/title/list")
               (list-consignments (get-consignments store) master-data)
               flash
               :html-class "list"))

     (GET "/consignment-new" {:keys [flash master-data ::store/store user-number]}
       (render (t "erp/title/new")
               (edit-consignment
                {:ref    (next-consignment-ref store user-number)
                 :load   {:date (w/format-date (Date.))}
                 :unload {:date (w/format-date (Date.))}
                 :status otm/status-draft}
                master-data)
               flash
               :html-class "details"))

     (POST "/consignment-new" {:keys [params]}
       (let [{:keys [ref] :as consignment}
             (-> params
                 (params->)
                 (assoc :id (str (UUID/randomUUID))))]
         (-> "."
             (redirect :see-other)
             (assoc :flash {:success (t "erp/flash/create-success" {:ref ref})})
             (assoc ::store/commands [[:put! :consignments consignment]]))))

     (GET "/consignment-:id" {:keys        [flash master-data ::store/store]
                              {:keys [id]} :params}
       (when-let [{:keys [ref] :as consignment} (get-consignment store id)]
         (render (t "erp/title/edit" {:ref ref})
                 (edit-consignment consignment master-data)
                 flash
                 :html-class "details")))

     (POST "/consignment-:id" {:keys [params]}
       (let [{:keys [ref] :as consignment} (params-> params)]
         (-> "."
             (redirect :see-other)
             (assoc :flash {:success (t "erp/flash/update-success" {:ref ref})})
             (assoc ::store/commands [[:put! :consignments consignment]]))))

     (DELETE "/consignment-:id" {:keys        [::store/store user-number]
                                 {:keys [id]} :params}
       (when-let [consignment (get-consignment store id)]
         (-> "deleted"
             (redirect :see-other)
             (assoc :flash {:success     (t "erp/flash/delete-success" consignment)
                            :consignment consignment}
                    ::store/commands [[:delete! :consignments id]]
                    ::events/commands [[:unsubscribe!
                                        (consignment->subscription consignment
                                                                   user-number
                                                                   site-id)]]))))

     (GET "/deleted" {:keys [flash], {:keys [consignment]} :flash}
       (render (t "erp/title/deleted")
               (deleted-consignment consignment flash)
               flash
               :html-class "delete"))

     (GET "/publish-:id" {:keys        [flash master-data ::store/store]
                          {:keys [id]} :params}
       (when-let [consignment (get-consignment store id)]
         (render (t "erp/title/publish" consignment)
                 (publish-consignment consignment master-data)
                 flash
                 :html-class "publish")))

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
               (assoc :flash {:success     (t "erp/flash/published-success" consignment)
                              :explanation [[(t "erp/explanation/send-consignment-wms")
                                             {:otm-object (otm/->transport-order transport-order master-data)}]
                                            [(t "erp/explanation/send-consignment-tms")
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
         (render (t "erp/title/published" consignment)
                 (published-consignment consignment master-data flash)
                 flash
                 :html-class "publish"))))))
