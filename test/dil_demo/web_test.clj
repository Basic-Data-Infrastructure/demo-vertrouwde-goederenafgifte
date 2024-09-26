;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [dil-demo.i18n :as i18n]
            [dil-demo.web :as sut]
            [nl.jomco.http-status-codes :as http-status]
            [ring.mock.request :refer [request]]))

(defn do-request [& args]
  ((i18n/wrap sut/handler) (apply request args)))

(deftest handler
  (testing "/"
    (let [{:keys [status headers]} (do-request :get "/")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))))

  (testing "/assets"
    (let [{:keys [status headers]} (do-request :get "/assets/base.css")]
      (is (= http-status/ok status))
      (is (= "text/css" (get headers "Content-Type")))))

  (testing "/not-found"
    (let [{:keys [status headers]} (do-request :get "/not-found")]
      (is (= http-status/not-found status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type"))))))
