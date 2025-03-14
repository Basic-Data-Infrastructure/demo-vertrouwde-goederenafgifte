;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms
  (:require [dil-demo.events.pulsar :as events.pulsar]
            [dil-demo.events.web :as events.web]
            [dil-demo.store :as store]
            [dil-demo.wms.api :as wms.api]
            [dil-demo.wms.web :as wms.web]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]))

(defn make-web-handler [config]
  (-> (wms.web/make-handler config)
      (store/wrap-truncate :transport-orders config)

      (events.web/wrap config)
      (events.pulsar/wrap-exec-commands config)

      (store/wrap config)))

(defn make-api-handler
  [config]
  (-> (wms.api/make-handler config)
      (wrap-params)
      (wrap-json-response)
      (store/wrap config)))
