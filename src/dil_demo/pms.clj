;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.pms
  (:require [dil-demo.pms.web :as pms.web]
            [dil-demo.portbase :as portbase]))

(defn make-web-handler [config]
  (-> (pms.web/make-handler config)
      (portbase/wrap-request-event (:portbase config))))
