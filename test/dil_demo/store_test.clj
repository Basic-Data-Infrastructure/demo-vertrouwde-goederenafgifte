;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.store-test
  (:require [clojure.test :refer [deftest is]]
            [dil-demo.store :as sut]))

(defmethod sut/sort-resources :x [_ coll] (reverse (sort coll)))

(deftest wrap-truncate
  (let [app (sut/wrap-truncate identity :x {:max-orders 3})]

    (is (= {:store {:x {}}} (app {}))
        "handle none")

    (is (= {:store {:x {}, :other "OTHER"}}
           (app {:store {:other "OTHER"}}))
        "leave others untouched")

    (is (= {:store {:x {1 "1", 2 "2", 3 "3"}}}
           (app {:store {:x {1 "1", 2 "2", 3 "3"}}}))
        "keep all")

    (is (= {:store          {:x {2 "2", 3 "3", 4 "4"}}
            :store/commands [[:delete! :x 1]]}
           (app {:store {:x {1 "1", 2 "2", 3 "3", 4 "4"}}}))
        "drop with smallest :ref")))
