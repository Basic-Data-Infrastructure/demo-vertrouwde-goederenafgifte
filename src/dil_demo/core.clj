;;; SPDX-FileCopyrightText: 2024, 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024, 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.core
  (:gen-class)
  (:require [dil-demo.config :as config]
            [dil-demo.events :as events]
            [dil-demo.store :as store]
            [dil-demo.web :as web]
            [environ.core :refer [env]]
            [nl.jomco.resources :as resources]
            [ring.adapter.jetty :refer [run-jetty]]))

(extend-protocol resources/Resource
  org.eclipse.jetty.server.Server
  (close [server] (.stop server)))

(defn run-system [config]
  (resources/mk-system
      [store  (store/make-store (-> config :store))
       events (events/make-resource (assoc config
                                           :resources {:store store}))
       webapp (web/make-app (assoc config
                                   :resources {:store store
                                               :events events}))
       _webserver (run-jetty webapp (-> config :jetty (assoc :join? false)))]))

(defn -main []
  (let [config (config/->config env)]
    (run-system config)
    (resources/wait-until-interrupted)))
