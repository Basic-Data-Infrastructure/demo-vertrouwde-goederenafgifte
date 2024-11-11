;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.client-data
  (:require [org.bdinetwork.ishare.client :as ishare-client]))


(defn ->client-data
  [{:keys [eori
           dataspace-id
           key-file chain-file
           ar-id ar-base-url ar-type
           satellite-id satellite-base-url]}]
  {:pre [eori dataspace-id key-file chain-file
         satellite-id satellite-base-url]}
  {:ishare/client-id                       eori
   :ishare/fetch-party-info-fn             (ishare-client/mk-cached-fetch-party-info 1000)
   :ishare/dataspace-id                    dataspace-id
   :ishare/satellite-id                    satellite-id
   :ishare/satellite-base-url              satellite-base-url
   :ishare/authorization-registry-id       ar-id
   :ishare/authorization-registry-base-url ar-base-url
   :ishare/authorization-registry-type     (keyword ar-type)
   :ishare/private-key                     (ishare-client/private-key key-file)
   :ishare/x5c                             (ishare-client/x5c chain-file)})
