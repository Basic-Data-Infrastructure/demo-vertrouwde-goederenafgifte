;;; SPDX-FileCopyrightText: 2024, 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024, 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.web
  (:require [clojure.string :refer [re-quote-replacement]]
            [clojure.tools.logging :as log]
            [compojure.core :refer [context GET routes]]
            [compojure.route :refer [resources]]
            [dil-demo.config :refer [->site-config]]
            [dil-demo.dcsa-events-connector :as dcsa-events-connector]
            [dil-demo.erp :as erp]
            [dil-demo.i18n :as i18n :refer [t]]
            [dil-demo.master-data :as master-data]
            [dil-demo.pms :as pms]
            [dil-demo.sites :refer [sites]]
            [dil-demo.store :as store]
            [dil-demo.tms :as tms]
            [dil-demo.web-utils :as w]
            [dil-demo.wms :as wms]
            [dil-demo.wms.events :as wms.events]
            [nl.jomco.ring-session-ttl-memory :refer [ttl-memory-store]]
            [ring.middleware.basic-authentication :refer [wrap-basic-authentication]]
            [ring.middleware.defaults :refer [api-defaults site-defaults wrap-defaults]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.util.response :refer [content-type not-found]]))

(defn not-found-handler [_]
  (-> (w/render-body "dil"
                     [:div.app-container
                      [:nav.app]
                      [:div.app
                       [:main.container
                        [:p (t "not-found")]
                        [:a.button {:href "/"} (t "button/start-screen")]]]]
                     :title (t "not-found/title")
                     :site-name "DIL-Demo"
                     :template-fn w/base-template)
      (not-found)
      (content-type "text/html; charset=utf-8")))

(defn list-apps [config]
  [:main.list-apps
   [:img.dil-logo {:src "/assets/dil-logo-en.png"}]
   [:ul
    (for [{:keys [path slug title]} sites]
      [:li {:class slug}
       [:a {:href path}
        [:div
         [:h2.site-name (get-in config [(keyword slug) :site-name])]
         [:span.site-title title]]]])]])

(defn wrap-max-age-cache-control
  "Add `max-age` directives for `Cache-Control` set to `seconds`.

  Note that `compojure.route/resources` adds a `Last-Modified`
  header (works for files in jar-files too) to allow the browser to
  make an `If-Modified-Since` request which will yield a `304 Not
  Modified` response if the resource has not been updated."
  [app seconds]
  (fn cache-asset-wrapper [req]
    (when-let [res (app req)]
      (assoc-in res [:headers "Cache-Control"]
                (str "must-revalidate, max-age=" seconds)))))

(def cache-control-max-age-assets 60)

(defn make-root-handler [config]
  (-> (routes
       (GET "/" {}
         (w/render "dil"
                   (list-apps config)
                   :title (t "start-screen/title")
                   :site-name "DIL-Demo"
                   :template-fn w/base-template))
       (resources "/")
       not-found-handler)
      (wrap-max-age-cache-control cache-control-max-age-assets)))

(defn wrap-log
  [handler]
  (fn log-wrapper [request]
    (let [response (handler request)]
      (log/info (str (:status response) " " (:request-method request) " " (:uri request)))
      response)))

(defn ->authenticate
  "Make authentication function.

  This function accepts `user` named with `user-prefix` and number
  between 1 and `max-accounts`.  When `passwd` matches the number
  multiplied by `pass-multi` it returns the user number."
  [{:keys [user-prefix pass-multi max-accounts]}]
  (let [user-re (re-pattern (str "^" (re-quote-replacement user-prefix) "(\\d+)$"))]
    (fn authenticate [user passwd]
      (when-let [[_ n-str] (re-matches user-re user)]
        (let [n (parse-long n-str)]
          (and (<= 1 n max-accounts)
               (= (str (* n pass-multi)) passwd)
               n))))))

(defn wrap-user-number
  "Set `user-number` on request from `basic-authentication`.

  Needs to be wrapped by `wrap-basic-authentication` middleware."
  [app]
  (fn user-number-wrapper [req]
    (let [{:keys [basic-authentication]} req]
      (app (if basic-authentication
             (-> req
                 (dissoc :basic-authentication)
                 (assoc :user-number basic-authentication))
             req)))))

(defn- wrap-h2m [handler config]
  (-> handler
      (wrap-user-number)
      (wrap-basic-authentication (->authenticate (config :auth)))

      (wrap-defaults (-> site-defaults
                         (assoc-in [:session :store] (ttl-memory-store))
                         (assoc-in [:static :resources] false)))))

(defn- wrap-m2m [handler user-number]
  (wrap-defaults (fn m2m-context-wrapper [req]
                   (-> req
                       (assoc :user-number (parse-long user-number))
                       (handler)))
                 api-defaults))

(defn make-app [config]
  (let [
        ;; NOTE: can not define these within `context` because that
        ;; gets reevaluated every time, causing session related stuff
        ;; to break
        erp-h2m   (-> (erp/make-web-handler (->site-config config :erp))

                      ;; hook up dcsa-events-connector container registration
                      (dcsa-events-connector/wrap-container-register)
                      (store/wrap (->site-config config :erp))

                      (wrap-h2m config))
        tms-1-h2m (wrap-h2m (tms/make-web-handler (->site-config config :tms-1)) config)
        tms-2-h2m (wrap-h2m (tms/make-web-handler (->site-config config :tms-2)) config)
        wms-h2m   (wrap-h2m (wms/make-web-handler (->site-config config :wms)) config)
        pms-h2m   (wrap-h2m (pms/make-web-handler (->site-config config :pms)) config)]
    (-> (routes
         ;;
         ;; human to machine routes
         ;;
         (context "/erp" [] erp-h2m)
         (context "/tms-1" [] tms-1-h2m)
         (context "/tms-2" [] tms-2-h2m)
         (context "/wms" [] wms-h2m)
         (context "/pms" [] pms-h2m)

         ;;
         ;; machine to machine routes
         ;;
         (context ["/:user-number/wms" :user-number #"\d+"] [user-number]
           (wrap-m2m (wms.events/make-api-handler (->site-config config :wms))
                     user-number))

         (context ["/:user-number/erp/event" :user-number #"\d+"] [user-number]
           (let [site-config (->site-config config :erp)]
             (-> (dcsa-events-connector/make-handler site-config)
                 (dcsa-events-connector/wrap-event-handler)
                 (erp/wrap-incoming-portbase-event)
                 (store/wrap site-config)
                 (wrap-m2m user-number))))

         ;;
         ;; fallback
         ;;
         (make-root-handler config))

        (master-data/wrap config)
        (i18n/wrap)

        (wrap-stacktrace)
        (wrap-log))))
