;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.sites)

(def sites
  [{:slug "erp", :path "/erp/", :title "ERP"}
   {:slug "wms", :path "/wms/", :title "WMS"}
   {:slug "tms-1", :path "/tms-1/", :title "TMS-1"}
   {:slug "tms-2", :path "/tms-2/", :title "TMS-2"}])
