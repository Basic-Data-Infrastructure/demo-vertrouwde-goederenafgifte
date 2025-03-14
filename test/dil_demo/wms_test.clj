;;; SPDX-FileCopyrightText: 2024, 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024, 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms-test
    (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [dil-demo.wms :as sut]
            [nl.jomco.http-status-codes :as http-status]
            [ring.mock.request :refer [request]]))

(def eori "EU.EORI.TEST")
(def other-eori "EU.EORI.OTHER")

(defn slurp-pem-data [s]
  (string/replace
   (->> s
        (io/resource)
        (slurp)
        (re-matches #"(?s)-----BEGIN (?:PRIVATE KEY|CERTIFICATE)-----\n(.*)-----END (?:PRIVATE KEY|CERTIFICATE)-----\n?")
        (second)
        )
   #"\n" ""))

(deftest make-api-handler
  (testing "availability of /epcis-event/connect/token endpoint"
    (let [priv-key    (slurp-pem-data "test/pem/client.key.pem")
          pub-key     (slurp-pem-data "test/pem/client.cert.pem")
          client-data #:ishare{:client-id          eori
                               :private-key        priv-key
                               :x5c                [pub-key]
                               :satellite-id       other-eori
                               :satellite-base-url "https://example.com"}
          handler     (sut/make-api-handler {:eori        eori
                                             :client-data client-data})]
      (is (= http-status/method-not-allowed
             (:status (handler (request :get "/epcis-event/connect/token")))))
      (is (= http-status/bad-request
             (:status (handler (request :post "/epcis-event/connect/token"))))))))
