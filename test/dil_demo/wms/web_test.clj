;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms.web-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [dil-demo.i18n :as i18n]
            [dil-demo.otm :as otm]
            [dil-demo.wms.web :as sut]
            [nl.jomco.http-status-codes :as http-status]
            [ring.mock.request :refer [request]]))

(def owner-eori "EU.EORI.OWNER")

(def store
  {:transport-orders
   {"31415"
    {:id "31415"
     :ref "31415"
     :status  otm/status-requested
     :load {:location-name "WAREHOUSE"}
     :owner {:eori owner-eori}}}})

(defn do-request [method path & [params]]
  ((-> {:site-id :wms, :site-name "WMS"}
       (sut/make-handler)
       (i18n/wrap :throw-exceptions true))
   (assoc (request method path params)
          :base-url "http://example.com"
          :store store
          :user-number 1
          :site-id :wms
          :master-data {:eori->name {}})))

(deftest handler
  (testing "GET /"
    (let [{:keys [status headers]} (do-request :get "/")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))))

  (testing "DELETE /transport-order-31415"
    (let [{:keys [status :store/commands]} (do-request :delete "/transport-order-31415")]
      (is (= http-status/see-other status))
      (is (= [:delete! :transport-orders] (->> commands first (take 2))))))

  (testing "GET /verify-not-found"
    (is (nil? (do-request :get "/verify-not-found"))))

  (testing "GET /verify-31415"
    (let [{:keys [status headers body]} (do-request :get "/verify-31415")]
      (is (= http-status/ok status))
      (is (= "text/html; charset=utf-8" (get headers "Content-Type")))
      (is (re-find #"<dd\b[^>]*>31415</dd>" body))))

  (testing "POST /verify-31415"
    "TODO, this calls out to satellite and ARs")

  (testing "POST /send-gate-out-31415"
    (let [{:keys          [status]
           event-commands :event/commands
           store-commands :store/commands}
          (do-request :post "/send-gate-out-31415")]
      (is (= http-status/see-other status))

      (testing "event triggered"
        (is (= (update-in event-commands [0 1] dissoc :message)
               [[:send! {:topic       "31415"
                         :owner-eori  "EU.EORI.OWNER"
                         :user-number 1
                         :site-id     :wms}]]))
        (is (re-matches #"http://example.com/1/wms/event/[0-9a-z-]+"
                        (get-in event-commands [0 1 :message]))))

      (testing "event payload stored"
        (let [{:keys [id body content-type targets]}
              (->> store-commands
                   (filter #(= [:put! :events] (take 2 %)))
                   first
                   last)]
          (is (= "application/json; charset=utf-8" content-type))
          (is (contains? targets owner-eori))
          (let [{:strs [eventId bizStep bizLocation]} (json/read-str body)]
            (is (= id eventId))
            (is (= "departing" bizStep))
            (is (= "WAREHOUSE" bizLocation))))))))
