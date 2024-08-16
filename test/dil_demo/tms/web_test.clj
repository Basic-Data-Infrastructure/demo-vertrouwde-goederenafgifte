;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.tms.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [dil-demo.tms.web :as sut]
            [dil-demo.store :as store]
            [dil-demo.events :as events]
            [nl.jomco.http-status-codes :as http-status]
            [ring.mock.request :refer [request]]))

(def store
  {:trips
   {"31415"
    {:id "31415"
     :status "assigned"
     :ref "31415"
     :owner {:eori "EU.EORI.OWNER"}}}})

(defn do-request [method path & [params]]
  ((sut/make-handler {:id :tms, :site-name "TMS"})
   (assoc (request method path params)
          ::store/store store
          :user-number 1
          :master-data {:carriers            {}
                        :eori->name          {}
                        :warehouses          {}
                        :warehouse-addresses {}})))

(deftest handler
  (testing "GET /"
    (let [{:keys [status headers body]} (do-request :get "/")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
      (is (re-find #"\b31415\b" body))))

  (testing "DELETE /trip-31415"
    (let [{:keys          [status]
           store-commands ::store/commands
           event-commands ::events/commands} (do-request :delete "/trip-31415")]
      (is (= http-status/see-other status))
      (is (= [[:delete! :trips "31415"]] store-commands))
      (is (= [[:unsubscribe! {:topic "31415",
                              :owner-eori "EU.EORI.OWNER"
                              :user-number 1}]]
             event-commands))))

  (testing "GET /assign-not-found"
    (is (nil? (do-request :get "/assign-not-found"))))

  (testing "GET /assign-31415"
    (let [{:keys [status headers body]} (do-request :get "/assign-31415")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
      (is (re-find #"\b31415\b" body))))

  (testing "POST /assign-31415"
    (let [{:keys [status headers]
           store-commands ::store/commands
           event-commands ::events/commands}
          (do-request :post "/assign-31415"
                      {:driver-id-digits "1234"
                       :license-plate    "AB-01-ABC"})]
      (is (= http-status/see-other status))
      (is (= "assigned-31415" (get headers "Location")))
      (is (= [:put! :trips] (->> store-commands first (take 2))))
      (is (= [[:subscribe! {:topic "31415",
                            :owner-eori "EU.EORI.OWNER"
                            :user-number 1}]]
             event-commands))))

  (testing "GET /outsource-31415"
    (let [{:keys [status headers body]} (do-request :get "/outsource-31415")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
      (is (re-find #"\b31415\b" body))))

  (testing "POST /outsource-31415"
    (let [{:keys [status headers]
           store-commands ::store/commands
           event-commands ::events/commands}
          (do-request :post "/outsource-31415"
                      {:carrier-eori "EU.EORI.OTHER"})]
      (is (= http-status/see-other status))
      (is (= "outsourced-31415" (get headers "Location")))
      (is (= [:put! :trips] (->> store-commands first (take 2))))
      (is (= [[:subscribe! {:topic "31415",
                            :owner-eori "EU.EORI.OWNER"
                            :user-number 1}]]
             event-commands))))

  (testing "GET /outsourced-31415"
    (let [{:keys [status headers body]} (do-request :get "/outsourced-31415")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
      (is (re-find #"\b31415\b" body))))

  (testing "GET /chauffeur/"
    (let [{:keys [status headers body]} (do-request :get "/chauffeur/")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
      (is (re-find #"\b31415\b" body))))

  (testing "GET /chauffeur/trip-not-found"
    (is (nil? (do-request :get "/assign-not-found"))))

  (testing "GET /chauffeur/trip-31415"
    (let [{:keys [status headers body]} (do-request :get "/chauffeur/trip-31415")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
      (is (re-find #"\b31415\b" body)))))
