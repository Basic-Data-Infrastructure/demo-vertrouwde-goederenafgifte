;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
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
            [dil-demo.erp :as erp]
            [dil-demo.events :as events]
            [dil-demo.i18n :as i18n :refer [t]]
            [dil-demo.master-data :as master-data]
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
                     [:main
                      [:p (t "not-found")]
                      [:a.button {:href "/"} (t "button/start-screen")]]
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

(defn- h2m-context [{:keys [site-id] :as site-config} make-handler]
  (context (str "/" (name site-id)) []
    (-> site-config
        (make-handler)
        (events/wrap-web site-config)
        (store/wrap site-config))))

(defn- h2m-routes [config]
  (-> (routes
       (h2m-context (->site-config config :erp) erp/make-web-handler)
       (h2m-context (->site-config config :tms-1) tms/make-web-handler)
       (h2m-context (->site-config config :tms-2) tms/make-web-handler)
       (h2m-context (->site-config config :wms) wms/make-web-handler)
       (make-root-handler config))

      (master-data/wrap config)

      (wrap-user-number)
      (wrap-basic-authentication (->authenticate (config :auth)))
      (i18n/wrap)

      (wrap-defaults (-> site-defaults
                         (assoc-in [:session :store] (ttl-memory-store))

                         ;; serve resource ourselves to allow applying cache-control
                         (assoc-in [:static :resources] false)))))

(defn- m2m-context [site-id config make-handler]
  (let [site-config (->site-config config site-id)
        handler     (make-handler site-config)]
    (context [(str "/:user-number/" (name site-id)) :user-number #"\d+"] [user-number]
      (wrap-defaults (fn m2m-context-wrapper [req]
                       (-> req
                           (assoc :eori (:eori site-config)
                                  :user-number (parse-long user-number))
                           (store/assoc-store site-config)
                           (handler)))
                     api-defaults))))

(defn wrap-base-url
  "Set base-url that should be used for generating urls back to the service."
  [f {:keys [base-url]}]
  (fn [req]
    (f (assoc req :base-url base-url))))

(defn make-app [config]
  (-> (routes
       (m2m-context :wms config wms.events/make-api-handler)
       (h2m-routes config))
      (wrap-base-url config)
      (wrap-stacktrace)
      (wrap-log)))
