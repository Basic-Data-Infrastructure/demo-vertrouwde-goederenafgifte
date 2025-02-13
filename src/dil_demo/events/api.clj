;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.events.api
  (:require [clojure.data.json :refer [json-str]]
            [compojure.core :refer [GET]]
            [dil-demo.events.pulsar :as events.pulsar]
            [dil-demo.i18n :refer [t]]
            [nl.jomco.http-status-codes :as http-status]
            [org.bdinetwork.ishare.jwt :as jwt]
            [org.bdinetwork.ring.authentication :as authentication]
            [org.bdinetwork.ring.remote-association :refer [remote-association]]
            [ring.util.response :as response]))

(defn- wrap-association [app client-data]
  (let [association (remote-association client-data)]
    (fn association-wrapper [req]
      (app (assoc req :association association)))))

(def event-path "epcis-event")

(defn url-for
  "URL to event in M2M part of this site."
  [{:keys [base-url user-number site-id]} event-id]
  (str base-url "/"
       user-number "/" (name site-id) "/"
       event-path "/" event-id))

(defn apply-epcis-event
  "Add store and event commands."
  [res
   {:keys [base-url site-id] :as _config}
   {:keys [user-number] :as _req}
   {:keys [order event targets]}]
  (let [event-id  (get event "eventId")
        event-url (url-for {:base-url    base-url
                            :user-number user-number
                            :site-id     site-id}
                           event-id)]
    (-> res
        (update :store/commands (fnil into [])
                [[:put! :events
                  {:id           event-id
                   :targets      targets
                   :content-type "application/json; charset=utf-8"
                   :body         (json-str event)}]])
        (update :event/commands (fnil conj [])
                [:send! (-> order
                            (events.pulsar/->subscription user-number site-id)
                            (assoc :message event-url))]))))



(defn make-route [{:keys [eori]}]
  (GET "/:id" {:keys        [context client-id store]
               {:keys [id]} :params}
    (if client-id
      (if-let [{:keys [body content-type targets]} (get-in store [:events id])]
        (if (contains? targets client-id)
          (-> body
              (response/response)
              (response/content-type content-type))
          (response/status http-status/forbidden))
        (response/not-found (t "events/no-found")))
      (let [token-endpoint (str context "/connect/token")]
        (-> "unauthorized"
            (response/response)
            (response/status http-status/unauthorized)
            (response/header "WWW-Authenticate"
                             (str "Bearer"
                                  " scope=\"iSHARE\""
                                  " server_eori=\"" eori "\""
                                  " server_token_endpoint=\"" token-endpoint "\"")))))))

(defn make-handler
  [{:keys                            [eori client-data]
    {:ishare/keys [private-key x5c]} :client-data
    :as                              config}]
  (let [public-key (jwt/x5c->first-public-key x5c)]
    (-> (make-route config)
        (authentication/wrap-authentication {:server-id                eori
                                             :public-key               public-key
                                             :private-key              private-key
                                             :access-token-ttl-seconds 10})

        ;; following middleware needed to maken wrap-authenication work
        (wrap-association client-data))))
