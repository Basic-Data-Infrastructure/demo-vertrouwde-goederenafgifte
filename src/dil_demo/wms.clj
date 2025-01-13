;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms
  (:require [dil-demo.events :as events]
            [dil-demo.store :as store]
            [dil-demo.wms.web :as wms.web]))

(defn make-web-handler [config]
  (-> (wms.web/make-handler config)
      (store/wrap-truncate :transport-orders config)

      (events/wrap-web config)
      (store/wrap config)))
