;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.web
  (:require [clojure.string :refer [re-quote-replacement]]
            [clojure.tools.logging :as log]
            [compojure.core :refer [GET routes]]
            [compojure.route :refer [resources]]
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
            [org.bdinetwork.ishare.client :as ishare-client]
            [ring.middleware.basic-authentication
             :refer [wrap-basic-authentication]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.util.response :refer [content-type not-found redirect]])
  (:import (java.util.regex Pattern)))

(defn rewrite-relative-redirect [res url-prefix]
  (let [loc (get-in res [:headers "Location"])]
    (if (and loc (not (re-matches #"^(/|\w*://).*" loc)))
      (update-in res [:headers "Location"] #(str url-prefix "/" %))
      res)))

(defn wrap-with-prefix [app url-prefix handler]
  (let [url-prefix-re (Pattern/compile (str "^" url-prefix "(/.*)"))]
    (fn prefix-wrapper [{:keys [uri] :as req}]
      (let [path (last (re-find url-prefix-re uri))]
        (cond
          path
          (or (handler (assoc req :uri path))
              (app req))

          (= uri url-prefix)
          (redirect (str uri "/"))

          :else
          (app req))))))

(defn not-found-handler [_]
  (-> (w/render-body "dil"
                     [:main
                      [:p (t "not-found")]
                      [:a.button {:href "/"} (t "button/start-screen")]]
                     :title (t "not-found/title")
                     :site-name "DIL-Demo")
      (not-found)
      (content-type "text/html; charset=utf-8")))

(defn list-apps []
  [:main
   [:ul
    (for [{:keys [path title]} sites]
      [:li [:a {:href path} title]])]])

(def handler
  (routes
   (GET "/" {}
     (w/render "dil"
               (list-apps)
               :title (t "start-screen/title")
               :site-name "DIL-Demo"))
   (resources "/")
   not-found-handler))

(defn wrap-log
  [handler]
  (fn [request]
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
  "Moves `basic-authentication` request key to `user-number` for
  clarity.  Needs to be wrapped by `wrap-basic-authentication`
  middleware."
  [app]
  (fn user-number-wrapper [req]
    (let [{:keys [basic-authentication]} req]
      (app (if basic-authentication
             (-> req
                 (dissoc :basic-authentication)
                 (assoc :user-number basic-authentication))
             req)))))

(defn wrap-h2m-app [app site-id {:keys [pulsar store-atom] :as config}
                    make-handler
                    make-event-handler]
  (let [config         (get config site-id)
        config         (assoc config
                              :site-id     site-id
                              :client-data (ishare-client/->client-data config)
                              :pulsar      pulsar
                              :store-atom  store-atom)
        event-callback (-> (if make-event-handler
                             (make-event-handler config)
                             (constantly nil))
                           (events/wrap config identity) ;; to allow unsubscribing
                           (store/wrap config))
        handler        (make-handler config)]
    (wrap-with-prefix app
                      (str "/" (name site-id))
                      (-> (fn wrap-h2m-app [req] (handler (assoc req :site-id site-id)))
                          (events/wrap-web config event-callback)
                          (store/wrap config)))))

(defn make-h2m-app [config]
  (-> handler
      (wrap-h2m-app :erp config erp/make-handler erp/make-event-handler)
      (wrap-h2m-app :wms config wms/make-handler nil)
      (wrap-h2m-app :tms-1 config tms/make-handler tms/make-event-handler)
      (wrap-h2m-app :tms-2 config tms/make-handler tms/make-event-handler)

      (master-data/wrap config)

      (wrap-user-number)
      (wrap-basic-authentication (->authenticate (config :auth)))
      (i18n/wrap)

      (wrap-defaults (assoc-in site-defaults
                               [:session :store] (ttl-memory-store)))))

(defn wrap-m2m-app [app id {:keys [store-atom] :as config} make-handler]
  (let [app-config  (get config id)
        app-config  (assoc app-config
                           :client-data (ishare-client/->client-data app-config)
                           :store-atom store-atom)
        handler (make-handler app-config)]
    (-> (fn [{:keys [uri] :as req}]
          (let [[_ base-uri user-number site-id uri]
                (re-matches #"(/(\d+)/([^/]+))(/.*)" uri)] ; /1/wms ipv /wms
            (if (and user-number site-id (= site-id (name id)))
              (-> req
                  (assoc :base-uri base-uri
                         :uri uri
                         :user-number (parse-long user-number)
                         :eori (:eori app-config))
                  (store/assoc-store app-config)
                  (handler))
              (app req))))

        ;; FIXME: enabling this breaks h2m anti-forgery middleware
        #_ (wrap-defaults api-defaults))))

(defn make-m2m-app [config]
  (-> (constantly nil)
      (wrap-m2m-app :wms config wms.events/make-handler)))

(defn wrap-base-url
  "Set base-url that should be used for generating urls back to the service."
  [f base-url]
  (fn [req]
    (f (assoc req :base-url base-url))))

(defn make-app [config]
  (let [ ;; NOTE: single atom to keep store because of publication among apps
        store-atom (store/get-store-atom (-> config :store :file))
        config     (assoc config :store-atom store-atom)]
    (-> (routes (make-m2m-app config)
                (make-h2m-app config))
        (wrap-stacktrace)
        (wrap-base-url (:base-url config))
        (wrap-log))))
