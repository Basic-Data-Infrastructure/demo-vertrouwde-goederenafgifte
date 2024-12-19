;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.events-test
  (:require [clojure.test :refer [deftest is]]
            [dil-demo.events :as sut]))

(defn- identity* [& args] args)

(deftest wrap-auto-unsubscribe
  (let [app   (sut/wrap-auto-unsubscribe identity :x identity* {:site-id "site"})
        store {:x {1 "data", 2 "other"}}]

    (is (empty? (-> {} (app) :event/commands))
        "delete none")

    (is (= [[:unsubscribe! ["data" "user" "site"]]]
           (-> {:user-number    "user"
                :store          store
                :store/commands [[:delete! :x 1]]}
               (app)
               :event/commands))
        "delete one, unsubscribe one")

    (is (= [[:unsubscribe! ["data" "user" "site"]]
            [:unsubscribe! ["other" "user" "site"]]]
           (-> {:user-number    "user"
                :store          store
                :store/commands [[:delete! :x 1]
                                 [:delete! :x 2]]}
               (app)
               :event/commands))
        "delete multiple, unsubscribe all")))
