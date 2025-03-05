;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.portbase-test
  (:require [babashka.http-client :as http]
            [clojure.test :refer [deftest is testing]]
            [dil-demo.portbase :as sut]))

(deftest exec
  (testing "subscribe"
    (with-redefs [http/request (fn [_req] {:body "{\"subscriptionId\": \"s1\"}"})]
      (let [config {}
            req    {}
            res    {:store/commands ['dummy]}
            args   {:callback-url "https://example.com/webhook"
                    :equipment-reference "c1"}
            res    (sut/exec config req res [:subscribe! args])]
        (is (= ['dummy
                [:put! :portbase-subscriptions
                 {:id {:equipment-reference "c1"}, :subscription-id "s1"}]]
               (:store/commands res))))))

  (testing "unsubscribe"
    (with-redefs [http/request (constantly nil)]
      (let [config {}
            req    {:store {:portbase-subscriptions {{:equipment-reference "c1"} {:subscription-id "s1"}}}}
            res    {:store/commands ['dummy]}
            args   {:equipment-reference "c1"}
            res    (sut/exec config req res [:unsubscribe! args])]
        (is (= ['dummy
                [:delete! :portbase-subscriptions {:equipment-reference "c1"}]]
               (:store/commands res)))))))
