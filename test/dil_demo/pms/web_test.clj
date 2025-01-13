;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.pms.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [dil-demo.i18n :as i18n]
            [dil-demo.pms.web :as sut]
            [nl.jomco.http-status-codes :as http-status]
            [ring.mock.request :refer [request]]))

(defn do-request [method path & [params]]
  ((-> {:site-id :pms, :site-name "PMS"}
       (sut/make-handler)
       (i18n/wrap :throw-exceptions true))
   (request method path params)))

(deftest handler
  (testing "GET /"
    (let [{:keys [status headers]} (do-request :get "/")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))))

  (testing "GET /equipment-gate-in"
    (let [{:keys [status headers]} (do-request :get "/equipment-gate-in")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))))

  (testing "GET /equipment-loaded"
    (let [{:keys [status headers]} (do-request :get "/equipment-loaded")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))))

  (testing "GET /transport-departed"
    (let [{:keys [status headers]} (do-request :get "/transport-departed")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))))

  (testing "POST /equipment-gate-in"
    (let [{:keys [status headers] :as res} (do-request :post "/equipment-gate-in"
                                                       {:equipment-reference "t1"
                                                        :location-short-name "RWG"})]
      (is (= http-status/see-other status))
      (is (= "event-requested" (get headers "Location")))
      (is (= :equipment-gate-in (get-in res [:request-portbase/events 0 :type])))))

  (testing "POST /equipment-loaded"
    (let [{:keys [status headers] :as res} (do-request :post "/equipment-loaded"
                                                       {:equipment-reference  "t1"
                                                        :location-short-name  "RWG"
                                                        :port-visit-reference "p1"})]
      (is (= http-status/see-other status))
      (is (= "event-requested" (get headers "Location")))
      (is (= :equipment-loaded (get-in res [:request-portbase/events 0 :type])))))

  (testing "POST /transport-departed"
    (let [{:keys [status headers] :as res} (do-request :post "/transport-departed"
                                                       {:port-visit-reference "p1"})]
      (is (= http-status/see-other status))
      (is (= "event-requested" (get headers "Location")))
      (is (= :transport-departed (get-in res [:request-portbase/events 0 :type]))))))
