;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.events.pulsar-test
  (:require [clojure.test :refer [deftest is]]
            [dil-demo.events.pulsar :as sut]))

(deftest wrap-auto-unsubscribe
  (let [app   (sut/wrap-auto-unsubscribe identity :x {:site-id "test-site"})
        store {:x {1 {:ref   "test-1"
                      :owner {:eori "owner-1"}}
                   2 {:ref   "test-2"
                      :owner {:eori "owner-2"}}}}]

    (is (empty? (-> {} (app) :event/commands))
        "delete none")

    (is (= [[:unsubscribe! {:topic       "test-1"
                            :owner-eori  "owner-1"
                            :user-number "user"
                            :site-id     "test-site"}]]
           (-> {:user-number    "user"
                :store          store
                :store/commands [[:delete! :x 1]]}
               (app)
               :event/commands))
        "delete one, unsubscribe one")

    (is (= [[:unsubscribe! {:topic       "test-1"
                            :owner-eori  "owner-1"
                            :user-number "user"
                            :site-id     "test-site"}]
            [:unsubscribe! {:topic       "test-2"
                            :owner-eori  "owner-2"
                            :user-number "user"
                            :site-id     "test-site"}]]
           (-> {:user-number    "user"
                :store          store
                :store/commands [[:delete! :x 1]
                                 [:delete! :x 2]]}
               (app)
               :event/commands))
        "delete multiple, unsubscribe all")))
