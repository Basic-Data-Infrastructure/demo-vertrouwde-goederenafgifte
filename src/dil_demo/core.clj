;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [dil-demo.events :as events]
            [dil-demo.store :as store]
            [dil-demo.web :as web]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.jomco.resources :as resources]
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

(defmethod envopts/parse :file
  [s _]
  ;; ensure that given file option exists, returns the path as string
  (if-not (.exists (io/file s))
    [nil "file does not exist"]
    [s]))

(def opts-spec
  {"ERP_EORI"              ["EORI of ERP system" :str :in [:erp :eori]]
   "WMS_EORI"              ["EORI of WMS system" :str :in [:wms :eori]]
   "TMS1_EORI"             ["EORI of TMS-1 system" :str :in [:tms-1 :eori]]
   "TMS2_EORI"             ["EORI of TMS-2 system" :str :in [:tms-2 :eori]]
   "DATASPACE_ID"          ["ID of Dataspace" :str]
   "SATELLITE_ID"          ["ID of Association Register" :str]
   "SATELLITE_ENDPOINT"    ["URL of Association Register" :str :in [:satellite-base-url]]
   "STORE"                 ["Database file" :str :default "/tmp/dil-demo.edn" :in [:store :file]]
   "PORT"                  ["Server port" :int :default 8080 :in [:jetty :port]]
   "BASE_URL"              ["Base URL" :str :default "http://localhost:8080"]
   "AUTH_USER_PREFIX"      ["Prefix of username" :str :default "demo" :in [:auth :user-prefix]]
   "AUTH_PASS_MULTI"       ["Multiplier for user password" :int :default 31415 :in [:auth :pass-multi]]
   "AUTH_MAX_ACCOUNTS"     ["Max number of user accounts" :int :default 42 :in [:auth :max-accounts]]
   "ERP_NAME"              ["ERP Name" :str :default "Smartphone Shop"  :in [:erp :site-name]]
   "ERP_AR_ID"             ["ERP AR ID" :str :in [:erp :ar-id]]
   "ERP_AR_ENDPOINT"       ["ERP AR URL" :str :in [:erp :ar-base-url]]
   "ERP_KEY_FILE"          ["ERP Key file" :file :in [:erp :key-file]]
   "ERP_CHAIN_FILE"        ["ERP Certificate chain file" :file :in [:erp :chain-file]]
   "WMS_NAME"              ["WMS Name" :str :default "Secure Storage Warehousing" :in [:wms :site-name]]
   "WMS_KEY_FILE"          ["WMS Key file" :file :in [:wms :key-file]]
   "WMS_CHAIN_FILE"        ["WMS Certificate chain file" :file :in [:wms :chain-file]]
   "TMS1_NAME"             ["TMS-1 Name" :str :default "Precious Goods Transport" :in [:tms-1 :site-name]]
   "TMS1_AR_ID"            ["TMS-1 AR ID" :str :in [:tms-1 :ar-id]]
   "TMS1_AR_ENDPOINT"      ["TMS-1 AR URL" :str :in [:tms-1 :ar-base-url]]
   "TMS1_KEY_FILE"         ["TMS-1 Key file" :file :in [:tms-1 :key-file]]
   "TMS1_CHAIN_FILE"       ["TMS-1 Certificate chain file" :file :in [:tms-1 :chain-file]]
   "TMS1_AR_TYPE"          ["TMS-1 AR Type" :str :in [:tms-1 :ar-type]]
   "TMS2_NAME"             ["TMS-2 Name" :str :default "Flex Transport" :in [:tms-2 :site-name]]
   "TMS2_AR_ID"            ["TMS-2 AR ID" :str :in [:tms-2 :ar-id]]
   "TMS2_AR_ENDPOINT"      ["TMS-2 AR URL" :str :in [:tms-2 :ar-base-url]]
   "TMS2_KEY_FILE"         ["TMS-2 Key file" :file :in [:tms-2 :key-file]]
   "TMS2_CHAIN_FILE"       ["TMS-2 Certificate chain file" :file :in [:tms-2 :chain-file]]
   "TMS2_AR_TYPE"          ["TMS-2 AR Type" :str :in [:tms-2 :ar-type]]
   "PULSAR_TOKEN_ENDPOINT" ["PULSAR Token Base-Url" :str :in [:pulsar :token-endpoint]]
   "PULSAR_SERVER_ID"      ["PULSAR Token Server ID" :str :in [:pulsar :token-server-id]]
   "PULSAR_URL"            ["PULSAR websocker URL" :str :in [:pulsar :url]]
   "PULSAR_DISABLED"       ["Disable events" :boolean :default false :in [:pulsar :disabled]]})

(defn process-config
  [config]
  (let [shared (select-keys config [:dataspace-id :satellite-id :satellite-base-url])]
    (-> config
        (update :erp merge shared)
        (update :wms merge shared)
        (update :tms-1 merge shared)
        (update :tms-2 merge shared))))

(defn ->config
  [env]
  (let [[config errs] (envopts/opts env opts-spec)]
    (when errs
      (throw (ex-info (str (envopts/errs-description errs) "\n"
                           "available options:\n"
                           (envopts/specs-description opts-spec))
                      {:errs   errs
                       :config config})))
    (process-config config)))

(extend-protocol resources/Resource
  org.eclipse.jetty.server.Server
  (close [server] (.stop server)))

(defn run-system [config]
  (resources/mk-system
      [store  (store/make-store (-> config :store))
       events (events/make-resource (assoc config :store store))
       webapp (web/make-app (assoc config
                                   :store store
                                   :events events))
       _webserver (run-jetty webapp (-> config :jetty (assoc :join? false)))]))

(defn -main []
  (run-system (->config env))
  (resources/wait-until-interrupted))
