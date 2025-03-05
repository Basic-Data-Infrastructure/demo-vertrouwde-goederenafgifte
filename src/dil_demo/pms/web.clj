;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.pms.web
  (:require [compojure.core :refer [GET POST routes]]
            [dil-demo.i18n :refer [t]]
            [dil-demo.web-form :as f]
            [dil-demo.web-utils :as w]
            [ring.util.response :refer [redirect]]))

(defn event-form [action & body]
  (f/form nil {:method    "POST"
               :action    action
               :fx-dialog "#modal-dialog"}
    (into [:fieldset.primary] body)
    [:section.actions
     (f/cancel-button)
     (f/submit-button {:class "request"
                       :label (t "button/request")})]))

(def location-short-names
  {"APMRTM"   "APM"
   "APMII"    "APM II"
   "ECTDELTA" "ECT DELTA"
   "RWG"      "RWG"})

(defn- input-equipment []
  (f/input :equipment-reference
           {:label    (t "label/equipment")
            :required true}))

(defn- select-location []
  (f/select :location-short-name
            {:label    (t "label/location-short-name")
             :required true
             :list     location-short-names}))

(defn- input-port-visit []
  (f/input :port-visit-reference
           {:label    (t "label/port-visit-reference")
            :required true}))

(defn- input-vessel-imo []
  (f/input :vessel-imo-number
           {:label    (t "label/vessel-imo-number")
            :required true}))

(defn equipment-gate-in-form []
  (event-form
   "equipment-gate-in"
   (input-equipment)
   (select-location)))

(defn equipment-loaded-form []
  (event-form
   "equipment-loaded"
   (input-equipment)
   (select-location)
   (input-port-visit)))

(defn transport-departed-form []
  (event-form
   "transport-departed"
   (input-port-visit)
   (input-vessel-imo)))

(defn list-forms []
  [:main
   [:section
    [:p (t "pms/list/description")]
    [:ul
     (for [type ["equipment-gate-in" "equipment-loaded" "transport-departed"]]
       [:li [:a.button.request {:href type, :fx-dialog "#modal-dialog"}
             (t (str "pms/title/" type))]])]]])

(defn event-requested [{:keys [explanation success]}]
  [:div
   [:section.primary
    [:p success]
    (w/explanation explanation)]])



(defn make-handler [{:keys [site-id site-name]}]
  (let [slug   (name site-id)
        render (fn render [title main flash & {:keys [slug-postfix html-class]}]
                 (w/render (str slug slug-postfix)
                           main
                           :flash flash
                           :title title
                           :site-name site-name
                           :html-class html-class))]
    (routes
     (GET "/" {:keys [flash]}
       (render (t "pms/title/list")
               (list-forms)
               flash
               :html-class "list"))

     (GET "/equipment-gate-in" {:keys [flash]}
       (render (t "pms/title/equipment-gate-in") (equipment-gate-in-form) flash))
     (POST "/equipment-gate-in" {:keys [params]}
       (-> "event-requested"
           (redirect :see-other)
           (assoc :flash {:success (t "pms/flash/equipment-gate-in-success")}
                  :request-portbase/events [(assoc params :type :equipment-gate-in)])))

     (GET "/equipment-loaded" {:keys [flash]}
       (render (t "pms/title/equipment-loaded") (equipment-loaded-form) flash))
     (POST "/equipment-loaded" {:keys [params]}
       (-> "event-requested"
           (redirect :see-other)
           (assoc :flash {:success (t "pms/flash/equipment-loaded-success")}
                  :request-portbase/events [(assoc params :type :equipment-loaded)])))

     (GET "/transport-departed" {:keys [flash]}
       (render (t "pms/title/transport-departed") (transport-departed-form) flash))
     (POST "/transport-departed" {:keys [params]}
       (-> "event-requested"
           (redirect :see-other)
           (assoc :flash {:success (t "pms/flash/transport-departed-success")}
                  :request-portbase/events [(assoc params :type :transport-departed)])))

     (GET "/event-requested" {:keys [flash]}
       (render (t "pms/title/event-requested")
               (event-requested flash)
               flash)))))
