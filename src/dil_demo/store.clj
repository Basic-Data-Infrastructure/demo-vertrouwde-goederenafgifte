;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.store
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [dil-demo.otm :as otm]))

(def table-key->spec
  {:consignments     ::otm/consignment
   :trips            ::otm/trip
   :transport-orders ::otm/transport-order})

(defn- check!
  "Run spec check on value."
  [table-key value]
  (when-let [spec (table-key->spec table-key)]
    (when-let [data (s/explain-data spec value)]
      (throw (ex-info (s/explain-str spec value)
                      {:spec    spec
                       :value   value
                       :explain data})))))

(defmulti commit (fn [_store _user-number _own-eori [cmd & _args]] cmd))

(defmethod commit :put! ;; put data in own database
  [store user-number own-eori [_cmd table-key {:keys [id] :as value}]]
  (check! table-key value)
  (swap! store assoc-in [user-number own-eori table-key id] value))

(defmethod commit :publish! ;; put data in other database
  [store user-number _own-eori [_cmd table-key target-eori {:keys [id] :as value}]]
  (check! table-key value)
  (swap! store assoc-in [user-number target-eori table-key id] value))

(defmethod commit :delete!
  [store user-number own-eori [_cmd table-key id]]
  (swap! store update-in [user-number own-eori table-key] dissoc id))

(defn load-store [filename]
  (let [file (io/file filename)]
    (if (.exists file)
      (edn/read-string (slurp file))
      {})))

;; TODO: race condition with `load-store`.  Make this an atomic file
;; write + move
(defn save-store [store filename]
  (spit (io/file filename) (pr-str store)))

(defn assoc-store [{:keys [user-number] :as req}
                   {:keys [store eori] :as _config}]
  (if (and eori store user-number)
    (assoc req :store (get-in @store [user-number eori]))
    req))

(defn process-store [{:keys [user-number] :as _req}
                     {:keys [store/commands] :as res}
                     {:keys [eori store] :as _config}]
  (when (and eori store user-number)
    (doseq [cmd commands]
      (log/debug "committing" cmd)
      (commit store user-number eori cmd)))
  res)

(defn wrap
  "Ring middleware providing storage.

  Provides :dil-demo.store/store key in request, containing the
  current state of store (read-only).

  When :dil-demo.store/commands key in response provides a collection
  of commands, those will be committed to the storage."
  [app config]
  (fn store-wrapper [req]
    (process-store req
                   (app (assoc-store req config))
                   config)))

(defn make-store
  "Return a store atom loaded with data from `file` (EDN format) and
  automatically stored to that file on changes."
  [{:keys [file]}]
  (let [store (atom (load-store file))]
    (add-watch store nil
               (fn [_ _ old-store new-store]
                 (when (not= old-store new-store)
                   (future (save-store new-store file)))))))
