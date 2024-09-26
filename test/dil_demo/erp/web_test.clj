;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.erp.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [dil-demo.erp.web :as sut]
            [dil-demo.events :as events]
            [dil-demo.i18n :as i18n]
            [dil-demo.otm :as otm]
            [dil-demo.store :as store]
            [nl.jomco.http-status-codes :as http-status]
            [ring.mock.request :refer [request]]))

(def eori "EU.EORI.TEST")

(def store
  {:consignments
   {"31415"
    {:id      "31415"
     :ref     "31415"
     :status  otm/status-draft
     :load    {:location-eori "EU.EORI.WAREHOUSE"}
     :carrier {:eori "EU.EORI.CARRIER"}
     :owner   {:eori eori}}}})

(defn do-request [method path & [params]]
  ((i18n/wrap
    (sut/make-handler {:site-id :erp, :site-name "ERP", :eori eori}))
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

  (testing "GET /consignment-new"
    (let [{:keys [status headers]} (do-request :get "/consignment-new")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))))

  (testing "POST /consignment-new"
    (let [{:keys [status ::store/commands]} (do-request :post "/consignment-new")]
      (is (= http-status/see-other status))
      (is (= [:put! :consignments] (->> commands first (take 2))))))

  (testing "GET /consignment-not-found"
    (is (nil? (do-request :get "/consignment-not-found"))))

  (testing "GET /consignment-31415"
    (let [{:keys [status headers body]} (do-request :get "/consignment-31415")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
      (is (re-find #"\b31415\b" body))))

  (testing "POST /consignment-31415"
    (let [{:keys [status ::store/commands]} (do-request :post "/consignment-31415")]
      (is (= http-status/see-other status))
      (is (= [:put! :consignments] (->> commands first (take 2))))))

  (testing "DELETE /consignment-31415"
    (let [{:keys          [status]
           store-commands ::store/commands
           event-commands ::events/commands}
          (do-request :delete "/consignment-31415")]
      (is (= http-status/see-other status))
      (is (= [:delete! :consignments] (->> store-commands first (take 2))))
      (is (= [[:unsubscribe! {:topic       "31415"
                              :owner-eori  "EU.EORI.TEST"
                              :user-number 1
                              :site-id     :erp}]]
             event-commands))))

  (testing "GET /publish-31415"
    (let [{:keys [status headers body]} (do-request :get "/publish-31415")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
      (is (re-find #"\b31415\b" body))))

  (testing "POST /publish-31415"
    (let [{:keys          [status]
           store-commands ::store/commands
           event-commands ::events/commands}
          (do-request :post "/publish-31415")]
      (is (= http-status/see-other status))
      (is (= #{:put! :publish!} (->> store-commands (map first) set)))
      (is (= event-commands
             [[:authorize! {:topic       "31415" :owner-eori "EU.EORI.TEST"
                            :read-eoris  ["EU.EORI.TEST" "EU.EORI.CARRIER"],
                            :write-eoris [ "EU.EORI.WAREHOUSE"]}]
              [:subscribe! {:topic       "31415"
                            :owner-eori  "EU.EORI.TEST"
                            :user-number 1
                            :site-id     :erp}]]))))

  (testing "GET /published-31415"
    (let [{:keys [status headers body]} (do-request :get "/publish-31415")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
      (is (re-find #"\b31415\b" body)))))
