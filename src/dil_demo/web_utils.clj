;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.web-utils
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [dil-demo.i18n :as i18n :refer [t]]
            [dil-demo.sites :refer [sites]]
            [ring.util.response :as response]
            [hiccup2.core :as hiccup])
  (:import (java.text SimpleDateFormat)
           (java.util UUID)))

(defn dummy-link [title]
  [:a.dummy
   {:onclick (str "alert(" (json/write-str (t "dummy-link")) ")")
    :title   (t "dummy-link")
    :href    "#"}
   title])

(defn template [site main & {:keys [app-name flash title site-name navigation]
                             :or   {navigation {:current :list
                                                :paths   {:list   "."
                                                          :pulses "pulses/"}}}}]
  (let [app-name (or app-name site)]
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport", :content "width=device-width,initial-scale=1.0"}]

      [:title (str title " — " site-name)]

      [:link {:rel "stylesheet", :href "/assets/base.css"}]
      [:link {:rel "stylesheet", :href "/assets/icons.css"}]
      [:link {:rel "stylesheet", :href (str "/assets/" site ".css")}]

      [:script {:src "/assets/qr-scanner.legacy.min.js"}] ;; https://github.com/nimiq/qr-scanner
      [:script {:src "/assets/scan-qr.js"}]
      [:script {:src "/assets/fx.js"}]]

     [:body
      [:nav.top
       [:ul
        [:li [:strong site-name]]]

       [:ul
        (for [{:keys [slug path title]} sites]
          [:li [:a {:href path, :class (when (= slug site) "current")}
                title
                [:span.site-sub-title (t (str "site-sub-title/" slug))]]])]

       [:ul.select-lang
        (for [lang (keys i18n/*translations*)]
          [:li
           [:a.set-lang
            {:href  (str ".?set-lang=" lang)
             :class (cond-> (str "lang-" lang)
                      (= i18n/*lang* lang) (str " current"))}
            lang]])]]

      [:div.app-container
       [:nav.app
        [:h1 site-name]
        [:h2 site]
        (let [{:keys [current paths]} navigation]
          [:ul
           [:li.dashboard {:class (when (= :dashboard current) "current")}
            (dummy-link (t "nav/dashboard"))]
           [:li.list {:class (when (= :list current) "current")}
            [:a {:href (:list paths)}
             (t (str "nav/" app-name "/list"))]]
           [:li.contacts {:class (when (= :contacts current) "current")}
            (let [title (t (str "nav/" app-name "/contacts"))]
              (if-let [path (:contacts paths)]
                [:a {:href path} title]
                (dummy-link title)))]
           [:li.pulses {:class (when (= :pulses current) "current")}
            [:a {:href (:pulses paths)}
             (t "nav/pulses")]]])]

       [:div.app
        [:header.container [:h1 title]]
        [:main.container
         (for [[type message] (select-keys flash [:error :success :warning])]
           [:article.flash {:class (str "flash-" (name type))} message])
         main]]]

      [:dialog#modal-dialog
       [:a.dialog-close {:href "."} "✕"]
       [:header]
       [:main]
       [:div.busy]]
      [:dialog#drawer-dialog
       [:a.dialog-close {:href "."} "✕"]
       [:header]
       [:main]
       [:div.busy]]]]))

(defn qr-code [text]
  (let [id (str "qrcode-" (UUID/randomUUID))]
    [:div.qr-code-container
     [:script {:src "/assets/qrcode.js"}] ;; https://davidshimjs.github.io/qrcodejs/

     [:div.qr-code {:id id}]
     [:script (hiccup/raw
               (str "new QRCode(document.getElementById("
                    (json/json-str id)
                    "), "
                    (json/json-str text)
                    ")"))]]))

(defn format-date [date]
  (.format (SimpleDateFormat. "yyyy-MM-dd") date))

(defn or-em-dash [val]
  (if (string/blank? val)
    "—"
    val))

(defn render-body [site main & opts]
  {:pre [(string? site) (coll? main)]}
  (str "<!DOCTYPE HTML>" (hiccup/html (apply template site main opts))))

(defn render [& args]
  (-> (apply render-body args)
      (response/response)
      (response/header "Content-Type" "text/html; charset=utf-8")))

(defn camelize
  "Convert key `s` from lispy to jsony style."
  [s]
  (let [words (string/split s #"-")]
    (string/join (into [(first words)] (map string/capitalize (rest words))))))

(defn to-json
  "Transform `val` from lispy value to jsony string."
  [val & {:keys [depth pad key-fn]
          :or   {depth  0
                 pad    "  "
                 key-fn name}
          :as   opts}]
  (let [padding (-> depth (repeat pad) (string/join))]
    (str
     padding
     (cond
       (map? val)
       (if (empty? val)
         "{}"
         (str "{\n"
              (string/join ",\n"
                           (for [[k v] val]
                             (str
                              padding pad
                              (to-json (key-fn k) (assoc opts :depth 0))
                              ": "
                              (string/trim (to-json v (assoc opts :depth (inc depth)))))))
              "\n"
              padding
              "}"))

       (coll? val)
       (if (empty? val)
         "[]"
         (str "[\n"
              (string/join ",\n"
                           (for [v val]
                             (to-json v (assoc opts :depth (inc depth)))))
              "\n"
              padding
              "]"))

       (instance? java.net.URI val)
       (json/write-str (str val) :escape-slash false)

       :else
       (json/write-str val :escape-slash false)))))

(defn otm-to-json [val]
  (to-json val :key-fn (comp camelize name)))

(defn server-description
  [{:ishare/keys [server-id server-name]}]
  (if server-name
    (str server-id " (" server-name ")")
    server-id))

(defmulti ishare-interaction-summary #(-> % :request :ishare/operation))

(defmethod ishare-interaction-summary :default
  [_]
  [:span (t "explanation/unknown")])

(defmethod ishare-interaction-summary :access-token
  [{:keys [request]}]
  [:span (t "explanation/ishare/access-token" {:party (server-description request)})])

(defmethod ishare-interaction-summary :parties
  [{:keys [request]}]
  [:span (t "explanation/ishare/parties" {:satellite (server-description request)})])

(defmethod ishare-interaction-summary :party
  [{{:ishare/keys [party-id] :as request} :request}]
  [:span (t "explanation/ishare/party" {:eori party-id, :satellite (server-description request)})])

(defmethod ishare-interaction-summary :ishare/policy
  [{:keys [request]}]
  [:span (t "explanation/ishare/policy" {:party (server-description request)})])

(defmethod ishare-interaction-summary :poort8/delete-policy
  [{:keys [request]}]
  [:span (t "explanation/poort8/delete-policy" {:party (server-description request)})])

(defmethod ishare-interaction-summary :poort8/policy
  [{:keys [request]}]
  [:span (t "explanation/poort8/create-policy" {:party (server-description request)})])

(defmethod ishare-interaction-summary :delegation-evidence
  [{:keys [request]}]
  [:span (t "explanation/ishare/delegation" {:party (server-description request)})])

(defn ishare-log-intercept-to-hiccup [logs]
  [:ol
   (for [interaction logs]
     [:li.interaction
      [:details
       [:summary (ishare-interaction-summary interaction)]
       (when (:request interaction)
         [:div.request
          [:p (t "explanation/http-request")]
          [:pre (to-json (-> interaction
                             :request
                             (select-keys [:method :uri :params :form-params :json-params :headers])))]])
       (when (:status interaction)
         [:div.response
          [:p (t "explanation/http-response")]
          [:pre (to-json (select-keys interaction [:status :headers :body]))]])]])])

(defn explanation [explanation]
  (when (seq explanation)
    [:details.explanation
     [:summary.button.secondary (t "explanation")]
     [:ol
      (for [[title {:keys [otm-object ishare-log event http-request http-response]}] explanation]
        [:li
         [:h3 title]
         (when otm-object
           [:details
            [:summary (t "explanation/otm-object")]
            [:pre (otm-to-json otm-object)]])
         (when ishare-log
           (ishare-log-intercept-to-hiccup ishare-log))
         (when http-request
           [:div.request
            [:p (t "explanation/http-request")]
            [:pre (to-json http-request)]])
         (when http-response
           [:div.response
            [:p (t "explanation/http-response")]
            [:pre (-> http-response
                      (dissoc :flash)
                      (to-json))]])
         (when event
           [:details
            [:summary (t "explanation/event")]
            [:pre (to-json event)]])])]]))

(defn append-explanation [res & explanation]
  (update-in res [:flash :explanation] (fnil into [])
             explanation))
