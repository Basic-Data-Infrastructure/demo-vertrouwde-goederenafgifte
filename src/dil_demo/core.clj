;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.core
  (:gen-class)
  (:require [dil-demo.events :as events]
            [dil-demo.web :as web]
            [clojure.java.io :as io]
            [ring.adapter.jetty :refer [run-jetty]]))

(defn get-env
  ([k default]
   (or (System/getenv k) default))
  ([k]
   (or (System/getenv k)
       (throw (Exception. (str "environment variable " k " not set"))))))

(defn get-filename
  [& [k :as args]]
  (let [fname (apply get-env args)]
    (when-not (.exists (io/file fname))
      (throw (ex-info (str "File `" fname "` does not exist")
                      {:fname fname
                       :key k})))
    fname))

(defn ->config []
  (let [erp-eori           (get-env "ERP_EORI")
        wms-eori           (get-env "WMS_EORI")
        tms-1-eori         (get-env "TMS1_EORI")
        tms-2-eori         (get-env "TMS2_EORI")
        dataspace-id       (get-env "DATASPACE_ID")
        satellite-id       (get-env "SATELLITE_ID")
        satellite-endpoint (get-env "SATELLITE_ENDPOINT")]
    {:jetty {:port (Integer/parseInt (get-env "PORT" "8080"))}
     :base-url (get-env "BASE_URL" (str "http://localhost:" (get-env "PORT" "8080")))
     :store {:file (get-env "STORE_FILE" "/tmp/dil-demo.edn")}
     :auth  {:user-prefix  (get-env "AUTH_USER_PREFIX" "demo")
             :pass-multi   (parse-long (get-env "AUTH_PASS_MULTI" "31415"))
             :max-accounts (parse-long (get-env "AUTH_MAX_ACCOUNTS" "42"))}
     :erp   {:eori               erp-eori
             :site-name          (get-env "ERP_NAME" "Smartphone Shop")
             :dataspace-id       dataspace-id
             :satellite-id       satellite-id
             :ar-id              (get-env "ERP_AR_ID")
             :ar-endpoint        (get-env "ERP_AR_ENDPOINT")
             :satellite-endpoint satellite-endpoint
             :key-file           (get-filename "ERP_KEY_FILE" (str "credentials/" erp-eori ".pem"))
             :chain-file         (get-filename "ERP_CHAIN_FILE" (str "credentials/" erp-eori ".crt"))}
     :wms   {:eori               wms-eori
             :site-name          (get-env "WMS_NAME" "Secure Storage Warehousing")
             :dataspace-id       dataspace-id
             :satellite-id       satellite-id
             :satellite-endpoint satellite-endpoint
             :key-file           (get-filename "WMS_KEY_FILE" (str "credentials/" wms-eori ".pem"))
             :chain-file         (get-filename "WMS_CHAIN_FILE" (str "credentials/" wms-eori ".crt"))}
     :tms-1 {:eori               tms-1-eori
             :site-name          (get-env "TMS1_NAME" "Precious Goods Transport")
             :dataspace-id       dataspace-id
             :satellite-id       satellite-id
             :satellite-endpoint satellite-endpoint
             :ar-id              (get-env "TMS1_AR_ID")
             :ar-endpoint        (get-env "TMS1_AR_ENDPOINT")
             :ar-type            (get-env "TMS1_AR_TYPE")
             :key-file           (get-filename "TMS1_KEY_FILE" (str "credentials/" tms-1-eori ".pem"))
             :chain-file         (get-filename "TMS1_CHAIN_FILE" (str "credentials/" tms-1-eori ".crt"))}
     :tms-2 {:eori               tms-2-eori
             :site-name          (get-env "TMS2_NAME" "Flex Transport")
             :dataspace-id       dataspace-id
             :satellite-id       satellite-id
             :satellite-endpoint satellite-endpoint
             :ar-id              (get-env "TMS2_AR_ID")
             :ar-endpoint        (get-env "TMS2_AR_ENDPOINT")
             :ar-type            (get-env "TMS2_AR_TYPE")
             :key-file           (get-filename "TMS2_KEY_FILE" (str "credentials/" tms-2-eori ".pem"))
             :chain-file         (get-filename "TMS2_CHAIN_FILE" (str "credentials/" tms-2-eori ".crt"))}

     :pulsar {:token-endpoint  (get-env "PULSAR_TOKEN_ENDPOINT")
              :token-server-id (get-env "PULSAR_SERVER_ID")
              :url             (get-env "PULSAR_URL")}}))

(defonce server-atom (atom nil))

(defn start-webserver [{config :jetty} app]
  (run-jetty app config))

(defn stop! []
  (when-let [server @server-atom]
    (.stop server)
    (reset! server-atom nil)
    (events/close-websockets!)))

(defn start! [config]
  (stop!)
  (reset! server-atom
          (start-webserver config (web/make-app config))))

(defn -main []
  (let [config (->config)]
    (start-webserver config (web/make-app config))))
