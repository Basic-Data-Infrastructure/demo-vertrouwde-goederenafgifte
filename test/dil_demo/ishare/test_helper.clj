;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.ishare.test-helper
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [dil-demo.ishare.client :as ishare-client]))

(defn build-client
  "Create a client useable with babashka.http-client/request.

  This client deliveries ring like request map to the given
  bi-directional channel and expects ring like response maps as
  response on the same channel."
  [c]
  (fn [request]
    (>!! c request)
    (<!! c)))

(defn run-exec
  "Run ishare client exec asynchronously returning a channel and a result future.

  The returns channel is bi-directional it delivers ring like request
  map and expects ring like response maps."
  [req]
  (let [c (async/chan)]
    [c (binding [ishare-client/http-client (build-client c)]
         (future
           (try
             (let [res (ishare-client/exec req)]
               (async/close! c)
               res)
             (catch Throwable e
               (>!! c {:exception e})
               (async/close! c)))))]))
