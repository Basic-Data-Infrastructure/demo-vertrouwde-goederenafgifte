;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms.web
  (:require [clojure.data.json :refer [json-str]]
            [clojure.string :as string]
            [compojure.core :refer [DELETE GET POST routes]]
            [dil-demo.events :as events]
            [dil-demo.i18n :refer [t]]
            [dil-demo.otm :as otm]
            [dil-demo.store :as store]
            [dil-demo.web-form :as f]
            [dil-demo.web-utils :as w]
            [dil-demo.wms.verify :as wms.verify]
            [dil-demo.wms.events :as wms.events]
            [ring.util.response :refer [redirect]])
  (:import (java.util UUID)
           (java.time Instant)))

(defn list-transport-orders [transport-orders]
  [:main
   (when-not (seq transport-orders)
     [:article.empty
      [:p (t "empty")]])

   (for [{:keys [id ref load goods status]} transport-orders]
     [:article
      [:header
       [:div.status (t (str "status/" status))]
       [:div.ref-date ref " / " (:date load)]]
      [:div.goods goods]


      [:footer.actions
       (cond
         (= status otm/status-confirmed)
         (f/post-button (str "send-gate-out-" id)
                        {:label (t "wms/button/gate-out")
                         :class "primary"})

         (= status otm/status-requested)
         [:a.button.primary {:href (str "verify-" id)} (t "wms/button/verify")])

       (f/delete-button (str "transport-order-" id))]])])

(defn qr-code-scan-button [carrier-id driver-id plate-id]
  (let [id (str "qr-code-video-" (UUID/randomUUID))]
    [:div.qr-code-scan-container
     [:video {:id id, :style "display:none"}]

     [:script {:src "/assets/qr-scanner.legacy.min.js"}] ;; https://github.com/nimiq/qr-scanner
     [:script {:src "/assets/scan-qr.js"}]
     [:a.button.secondary {:onclick (str "scanDriverQr(this, "
                                         (json-str id) ", "
                                         (json-str carrier-id) ", "
                                         (json-str driver-id) ", "
                                         (json-str plate-id) ")")}
      (t "wms/button/scan-qr")]]))

(defn verify-transport-order [{:keys [id] :as transport-order}]
  (f/form transport-order {:method "POST", :action (str "verify-" id)}
    (f/input :ref {:label (t "label/ref"), :disabled true})
    (f/input [:load :date] {:label (t "label/date"), :disabled true})
    (f/input :goods {:label (t "label/goods"), :disabled true})

    (when-not (string/blank? (:remarks load))
      (f/textarea [:load :remarks] {:label (t "label/remarks"), :disabled true}))

    [:div.actions
     (qr-code-scan-button "carrier-eoris" "driver-id-digits" "license-plate")]

    (f/text :carrier-eoris {:id       "carrier-eoris"
                            :label    (t "label/carrier-eories")
                            :required true})
    (f/text :driver-id-digits {:id       "driver-id-digits"
                               :label    (t "label/driver-id-digits")
                               :required true})
    (f/text :license-plate {:id       "license-plate"
                            :label    (t "label/license-plate")
                            :required true})

    [:div.actions
     [:button.button-primary
      {:type    "submit"
       :onclick (f/confirm-js (t "confirm/driver-and-license-plate"))}
      (t "wms/button/verify")]
     [:a.button {:href "."} (t "button/cancel")]]))

(defn accepted-transport-order [transport-order
                                {:keys [carrier-eoris driver-id-digits license-plate]}
                                {:keys [explanation]}
                                {:keys [eori->name]}]
  [:div
   [:section.verification-accepted
    [:p
     (t "wms/verification-accepted"
        {:ref              (:ref transport-order)
         :carrier          (or (-> carrier-eoris last eori->name)
                               (last carrier-eoris))
         :driver-id-digits driver-id-digits
         :license-plate    license-plate})]
    [:div.actions
     [:a.button {:href "."} (t "button/list")]]]
   (w/explanation explanation)])

(defn rejected-transport-order [transport-order
                                {:keys [carrier-eoris driver-id-digits license-plate]}
                                {:keys [explanation] :as result}
                                {:keys [eori->name]}]
  [:div
   [:section.verification-rejected
    [:p
     (t "wms/verification-rejected"
        {:ref              (:ref transport-order)
         :carrier          (or (-> carrier-eoris last eori->name)
                               (last carrier-eoris))
         :driver-id-digits driver-id-digits
         :license-plate    license-plate})]

    [:p
     (t "wms/verification-rejection-results"
        {:party (eori->name (wms.verify/rejection-eori result))})]

    [:ul.rejections
     (for [rejection (wms.verify/rejection-reasons result)]
       [:li rejection])]

    [:div.actions
     [:a.button {:href "."} (t "button/list")]]]
   (w/explanation explanation)])

(defn gate-out-transport-order [_transport-order {:keys [explanation]}]
  [:div
   [:div.actions
     [:a.button {:href "."} (t "button/list")]]
   (w/explanation explanation)])



(defn get-transport-orders [store]
  (->> store :transport-orders vals (sort-by :ref) (reverse)))

(defn get-transport-order [store id]
  (get-in store [:transport-orders id]))


(defn transport-order->subscription [{:keys [ref], {:keys [eori]} :owner}
                                     user-number
                                     site-id]
  {:topic       ref
   :owner-eori  eori
   :user-number user-number
   :site-id     site-id})

(defn make-handler [{:keys [site-id site-name client-data]}]
  (let [slug   (name site-id)
        render (fn render [title main flash & {:keys [slug-postfix]}]
                 (w/render (str slug slug-postfix)
                           main
                           :flash flash
                           :title title
                           :site-name site-name))]
    (routes
     (GET "/" {:keys        [flash]
               ::store/keys [store]}
       (render (t "wms/title/list")
               (list-transport-orders (get-transport-orders store))
               flash))

     (DELETE "/transport-order-:id" {::store/keys [store]
                                     {:keys [id]} :params}
       (when (get-transport-order store id)
         (-> "."
             (redirect :see-other)
             (assoc :flash {:success (t "wms/flash/delete-success")})
             (assoc ::store/commands [[:delete! :transport-orders id]]))))

     (GET "/verify-:id" {:keys        [flash]
                         ::store/keys [store]
                         {:keys [id]} :params}
       (when-let [transport-order (get-transport-order store id)]
         (render (t "wms/title/verify")
                 (verify-transport-order transport-order)
                 flash)))

     (POST "/verify-:id" {:keys                   [flash master-data ::store/store]
                          {:keys [id] :as params} :params}
       (when-let [transport-order (get-transport-order store id)]
         (let [params (update params :carrier-eoris string/split #",")
               result (wms.verify/verify! client-data transport-order params)]
           (if (wms.verify/permitted? result)
             (-> (render (t "wms/title/verification-accepted")
                         (accepted-transport-order transport-order params result master-data)
                         flash)
                 (assoc ::store/commands [[:put! :transport-orders
                                           (assoc transport-order :status otm/status-confirmed)]]))
             (render (t "wms/title/verification-rejected")
                     (rejected-transport-order transport-order params result master-data)
                     flash)))))

     (POST "/send-gate-out-:id" {:keys        [master-data ::store/store user-number]
                                 {:keys [id]} :params
                                 :as          req}
       (when-let [{:keys [ref] :as transport-order} (get-transport-order store id)]
         (let [event-id  (str (UUID/randomUUID))
               event-url (wms.events/url-for req event-id)
               targets   (wms.events/transport-order-gate-out-targets transport-order)
               tstamp    (Instant/now)
               body      (wms.events/transport-order-gate-out-body transport-order
                                                                   {:event-id   event-id
                                                                    :time-stamp tstamp}
                                                                   master-data)]
           (-> (str "sent-gate-out-" id)
               (redirect :see-other)
               (assoc :flash {:success (t "wms/flash/send-gate-out-success" {:ref ref})}
                      ::store/commands [[:put! :transport-orders
                                         (assoc transport-order :status otm/status-in-transit)]
                                        [:put! :events
                                         {:id           event-id
                                          :targets      targets
                                          :content-type "application/json; charset=utf-8"
                                          :body         (json-str body)}]]
                      ::events/commands [[:send! (-> transport-order
                                                     (transport-order->subscription user-number site-id)
                                                     (assoc :message event-url))]])))))

     (GET "/sent-gate-out-:id" {:keys        [flash ::store/store]
                                {:keys [id]} :params}
       (when-let [transport-order (get-transport-order store id)]
         (render (t "wms/title/sent-gate-out")
                 (gate-out-transport-order transport-order flash)
                 flash))))))
