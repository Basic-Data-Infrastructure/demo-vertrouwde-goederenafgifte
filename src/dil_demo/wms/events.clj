;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms.events
  (:require [dil-demo.store :as store]
            [nl.jomco.http-status-codes :as http-status]
            [org.bdinetwork.ishare.jwt :as jwt]
            [org.bdinetwork.service-provider.authentication :as authentication]
            [org.bdinetwork.service-provider.remote-association
             :refer [remote-association]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as response]))

(defn handler
  "Handle `/event/:uuid` requests or return `nil`.  Returns `401
  Unauthorized with `WWW-Authenticate` header when `client-id` not set
  to direct caller to token endpoint."
  [{:keys [base-uri client-id eori request-method uri ::store/store]}]
  {:pre [base-uri request-method uri store]}
  (when-let [[_ id] (and (= request-method :get) (re-matches #"/event/([a-f0-9-]+)" uri))]
    (if client-id
      (if-let [{:keys [body content-type targets]} (get-in store [:events id])]
        (if (contains? targets client-id)
          (-> (response/response body)
              (response/content-type content-type))
          (response/status http-status/forbidden))
        (response/not-found "Unknown event"))
      (let [token-endpoint (str base-uri "/connect/token")]
        (-> "unauthorized"
            (response/response)
            (response/status http-status/unauthorized)
            (response/header "WWW-Authenticate"
                             (str "Bearer"
                                  " scope=\"iSHARE\""
                                  " server_eori=\"" eori "\""
                                  " server_token_endpoint=\"" token-endpoint "\"")))))))

(defn url-for [{:keys [base-url user-number app-id]} event-id]
  (str base-url "/" user-number "/" (name app-id) "/event/" event-id))

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

(defn make-handler [{:keys                                   [eori client-data]
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
