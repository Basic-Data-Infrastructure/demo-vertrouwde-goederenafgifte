;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.i18n
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [clj-yaml.core :as yaml])
  (:import (java.time ZonedDateTime)
           (java.time.temporal ChronoUnit)))

(def ^:dynamic lang "nl")

(defn- check-translations
  "Throw an exception when languages in `translations` (top level keys)
  do not have the same keys."
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

(defn- read-translations []
  (with-open [rd (io/reader (io/resource "i18n.yml"))]
    (yaml/parse-stream rd :keywords false)))

(def translations
  (delay (-> (read-translations)
             (check-translations))))

(def languages (delay (set (keys @translations))))

(defn has-language? [^String lang]
  (contains? (into #{} (map name @languages)) lang))

(defn cookie-expires []
  (.plus (ZonedDateTime/now) 1 ChronoUnit/YEARS))

(def ^:dynamic throws-exception true)

(defn wrap [app & {:keys [throw-exceptions] :or {throw-exceptions true}}]
  (fn i18n-wrapper [{:keys [cookies] :as req}]
    (let [{{:keys [set-lang]} :params} req
          set-lang                     (and set-lang (has-language? set-lang) set-lang)]
      (binding [lang             (or set-lang
                                     (get-in cookies ["dil-demo-lang" :value] lang))
                throws-exception throw-exceptions]
        (assoc-in (app req)
                  [:cookies "dil-demo-lang"]
                  {:value   lang
                   :path    "/"
                   :expires (cookie-expires)})))))

(defn t-count [v args]
  (if (and (:count args) (map? v))
    (cond
      (and (= (:count args) 0) (:zero v)) (:zero v)
      (and (= (:count args) 1) (:one v)) (:one v)
      :else (:other v))
    v))

(defn t [& ks]
  (let [ks        (into [lang] ks)
        [ks args] (if (map? (last ks))
                    [(butlast ks) (last ks)]
                    [ks {}])
        val       (-> @translations
                      (get-in ks)
                      (t-count args))]
    (reduce (fn [r [k v]]
              (string/replace r (str "%{" (name k) "}") (str v)))
            (cond
              (string? val) val
              throws-exception (throw (ex-info "Translation missing" {:keys ks}))
              :else (pr-str ks))
            args)))
