;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms.events
  (:require [compojure.core :refer [GET]]
            [dil-demo.i18n :refer [t]]
            [nl.jomco.http-status-codes :as http-status]
            [org.bdinetwork.ishare.jwt :as jwt]
            [org.bdinetwork.ring.authentication :as authentication]
            [org.bdinetwork.ring.remote-association :refer [remote-association]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as response]))

(def handler
  (GET "/event/:id" {:keys [context client-id eori store]
                     {:keys [id]} :params}
    (if client-id
      (if-let [{:keys [body content-type targets]} (get-in store [:events id])]
        (if (contains? targets client-id)
          (-> (response/response body)
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

(defn url-for
  "URL to event in M2M part of this site."
  [{:keys [base-url user-number site-id]} event-id]
  (str base-url "/" user-number "/" (name site-id) "/event/" event-id))

(defn transport-order-gate-out-targets [transport-order]
  #{(-> transport-order :owner :eori)
    (-> transport-order :carrier :eori)}) ;; TODO what about outsourcing party?

(defn transport-order-gate-out-body [{:keys [load]}
                                     {:keys [time-stamp event-id]}
                                     {:keys [eori->name]}]
  {:type        "ObjectEvent"
   :eventTime   time-stamp
   :eventId     event-id
   :action      "OBSERVE"
   :disposition "in_transit"
   :bizStep     "departing"
   :bizLocation (or (:location-name load)
                    (eori->name (:location-eori load)))})

(defn wrap-association [app client-data]
  (let [association (remote-association client-data)]
    (fn association-wrapper [req]
      (app (assoc req :association association)))))

(defn make-api-handler [{:keys                            [eori client-data]
                         {:ishare/keys [private-key x5c]} :client-data}]
  (let [public-key (jwt/x5c->first-public-key x5c)]
    (-> handler
        (authentication/wrap-authentication {:server-id                eori
                                             :public-key               public-key
                                             :private-key              private-key
                                             :access-token-ttl-seconds 10})

        ;; following middleware needed to maken wrap-authenication work
        (wrap-association client-data)
        (wrap-params)
        (wrap-json-response))))
