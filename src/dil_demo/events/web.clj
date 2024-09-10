;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.events.web
  (:require [babashka.http-client :as http]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [compojure.core :refer [GET routes routing]]
            [dil-demo.http-utils :as http-utils]
            [dil-demo.web-utils :as w]
            [nl.jomco.http-status-codes :as http-status]
            [org.bdinetwork.ishare.client :as ishare-client]))

(defn fetch-event [url client-data]
  (let [req {:uri url, :method :get}
        res (-> req
                (assoc :throw false)
                (http/request)
                ;; remove request added by http client
                (dissoc :request))
        res (w/append-explanation res
                                  ["Openhalen zonder authenticatie"
                                   {:http-request  req
                                    :http-response res}])]
    (if-let [[auth-scheme {scope       "scope"
                           server-eori "server_eori"
                           server-path "server_token_endpoint"}]
             (and (= http-status/unauthorized (:status res))
                  (-> res
                      (get-in [:headers "www-authenticate"])
                      (http-utils/parse-www-authenticate)))]
      (if (and (= "Bearer" auth-scheme)
               (= "iSHARE" scope)
               server-eori
               server-path)
        (binding [ishare-client/log-interceptor-atom (atom [])]
          (let [token (-> client-data
                          (assoc :ishare/message-type :access-token
                                 :ishare/endpoint url
                                 :ishare/server-id server-eori
                                 :ishare/path server-path)
                          (ishare-client/exec)
                          :ishare/result)
                req   (assoc-in req [:headers "Authorization"]
                              (str "Bearer " token))
                res   (-> req
                        (assoc :throw false)
                        (http/request)
                        ;; get flash/explaination from earlier request
                        (assoc :flash (get res :flash))
                        ;; remove request added by http client
                        (dissoc :request))]
            (-> res
                (w/append-explanation ["Authenticatie token ophalen"
                                       {:ishare-log @ishare-client/log-interceptor-atom}])
                (w/append-explanation ["Ophalen met authenticatie token"
                                       {:http-request  req
                                        :http-response res}]))))
        res)
      res)))



(defn- list-pulses [pulses]
  [:main
   (when-not (seq pulses)
     [:article.empty
      [:p "Nog geen pulses geregistreerd.."]])
   (for [{:keys [id publishTime payload subscription]}
         (->> pulses vals (sort-by :publishTime) reverse)]
     [:article
      [:header
       [:div.status publishTime]
       [:div.subscription (string/join " / " subscription)]
       [:a {:href id}
        [:pre (w/to-json payload)]]]])])

(defn- show-pulse [{:keys [flash] :as res}]
  (let [res (dissoc res :flash)]
    [:main
     [:article
      [:pre (w/to-json (-> res
                           :body
                           (json/read-str)))]]
     (w/explanation (:explanation flash))]))

(defn- make-handler
  "Handler on /pulses/"
  [{:keys [id site-name client-data]}]
  (routes
   (GET "/pulses/" {:keys [pulses flash]}
     (w/render (name id)
               (list-pulses pulses)
               :flash flash
               :title "Pulses"
               :site-name site-name))
   (GET "/pulses/:id" {:keys [pulses params flash]}
     (when-let [{:keys [payload]} (get pulses (:id params))]
       (let [res (fetch-event payload client-data)]
         (w/render (name id)
                   (show-pulse res)
                   :flash flash
                   :title "Pulse"
                   :site-name site-name))))))

(defn wrap
  "Add route /pulses serving basic screen for viewing received pulses."
  [app config]
  (let [handler (make-handler config)]
    (fn [req]
      (routing req handler app))))
