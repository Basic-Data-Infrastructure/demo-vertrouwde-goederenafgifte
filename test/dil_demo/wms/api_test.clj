;;; SPDX-FileCopyrightText: 2024, 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024, 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.wms.api-test
  (:require [clojure.core.match :refer [match]]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [dil-demo.epcis :as epcis]
            [dil-demo.otm :as otm]
            [dil-demo.wms.api :as sut]))

(def transport-order
  {:ref     "test-ref"
   :owner   {:eori "EORI-OWNER"}
   :carrier {:eori "EORI-CARRIER"}
   :load    {:location-eori "EORI-LOCATION"}})

(deftest apply-epcis-departing-event
  (let [r (sut/apply-epcis-departing-event
           {}
           {:base-url "https://example.com"
            :site-id  "test-site"}
           {:user-number 314
            :master-data {:eori->name identity}}
           transport-order)]
    (testing "store commands"
      (is (= 2 (count (:store/commands r)))
          "Got 2 store commands")

      (testing "updating transport-order in local store"
        (let [[_ _ transport-order] (->> (:store/commands r)
                                         (filterv #(= [:put! :transport-orders] (take 2 %)))
                                         (first))]
          (is transport-order
              "got store command to update transport order")
          (is (= otm/status-in-transit (:status transport-order))
              "setting status to in-transit")))

      (testing "adding event to local store"
        (let [[_ _ event] (->> (:store/commands r)
                               (filterv #(= [:put! :events] (take 2 %)))
                               (first))]
          (is event
              "got store command to add event")
          (is (-> event :body (json/read-str) (epcis/departing?))
              "it's an epcis departing event"))))

    (testing "event commands"
      (is (= 1 (count (:event/commands r))))

      (match [(:event/commands r)]
        [([[:send! event]] :seq)]
        (testing "command"
          (is (= "test-ref" (:topic event)))
          (is (= "EORI-OWNER" (:owner-eori event)))
          (is (= 314 (:user-number event)))
          (is (= "test-site" (:site-id event)))
          (is (string/starts-with? (:message event)
                                   "https://example.com/314/test-site/epcis-event/")))))))
