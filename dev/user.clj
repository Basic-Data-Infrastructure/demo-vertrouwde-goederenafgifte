;;; SPDX-FileCopyrightText: 2024, 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024, 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(in-ns 'clojure.core)

(defn pk
  "Peek value for debugging."
  ([v] (prn v) v)
  ([k v] (prn k v) v))

(defn pk->
  "Peek value for debugging."
  ([v] (prn v) v)
  ([v k] (prn k v) v))

(ns user
  (:require [babashka.http-client :as http]
            [clojure.data.json :as json]
            [dil-demo.config :as config]
            [dil-demo.core :as core]
            [dil-demo.portbase :as portbase]
            [environ.core :refer [env]]
            [nl.jomco.resources :refer [close defresource]]))

(defresource system)

(defn stop! []
  (when system
    (close system)))

(defn config [& ks]
  (get-in (config/->config env) ks))

(defn start! []
  (defresource system (core/run-system (config/->config env))))

(defn portbase-subscriptions []
  (->> (portbase/get-subscriptions (config :portbase))
       (http/request)
       :body
       (json/read-str)
       (map #(get % "subscriptionId"))))

(defn portbase-unsubscribe [id]
  (->> id (portbase/unsubscribe (config :portbase)) (http/request)))

(defn portbase-unsubscribe-all []
  (doseq [id (portbase-subscriptions)]
    (portbase-unsubscribe id)))
