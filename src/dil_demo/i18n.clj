;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.i18n
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.logging :as log])
  (:import (java.time ZonedDateTime)
           (java.time.temporal ChronoUnit)))

(def ^:dynamic *lang* "nl")
(def ^:dynamic *translations* nil)
(def ^:dynamic *throws-exception* false)

(defn- check-translations
  "Throw an exception when languages in `translations` do not have the same keys."
  [translations]
  (let [langs (keys translations)
        all-keys (into #{} (->> translations
                                (map second)
                                (mapcat keys)
                                (set)))
        missing (reduce (fn [m lang]
                          (let [d (set/difference all-keys
                                                  (-> translations (get lang) keys))]
                            (if (seq d)
                              (assoc m lang d)
                              m)))
                        {}
                        langs)]
    (when (seq missing)
      (throw (ex-info "missing translations" missing)))

    translations))

(defn- read-translations [file]
  (with-open [rd (io/reader file)]
    (yaml/parse-stream rd :keywords false)))

(defn cookie-expires []
  (.plus (ZonedDateTime/now) 1 ChronoUnit/YEARS))

(defn wrap [app & {:keys [translations throw-exceptions]
                   :or   {throw-exceptions false
                          translations     (->  "i18n.yml"
                                                (io/resource)
                                                (read-translations)
                                                (check-translations))}}]
  (fn i18n-wrapper [{:keys [cookies] :as req}]
    (let [{{:keys [set-lang]} :params} req
          set-lang                     (when (contains? translations set-lang)
                                         set-lang)
          cookie-lang                  (get-in cookies ["dil-demo-lang" :value] *lang*)]
      (binding [*lang*             (or set-lang cookie-lang)
                *translations*     translations
                *throws-exception* throw-exceptions]
        (when-let [res (app req)]
          (assoc-in res [:cookies "dil-demo-lang"]
                    {:value   *lang*
                     :path    "/"
                     :expires (cookie-expires)}))))))

(defn t-count [v args]
  (if (and (:count args) (map? v))
    (cond
      (and (= (:count args) 0) (:zero v)) (:zero v)
      (and (= (:count args) 1) (:one v)) (:one v)
      :else (:other v))
    v))

(defn t [& ks]
  (let [ks        (into [*lang*] ks)
        [ks args] (if (map? (last ks))
                    [(butlast ks) (last ks)]
                    [ks {}])
        val       (-> *translations*
                      (get-in ks)
                      (t-count args))]
    (reduce (fn [r [k v]]
              (string/replace r (str "%{" (name k) "}") (str v)))
            (cond
              (string? val)
              val

              *throws-exception*
              (throw (ex-info "Translation missing" {:keys ks}))

              :else
              (do (log/error "Translation missing" {:keys ks})
                  (pr-str ks)))
            args)))
