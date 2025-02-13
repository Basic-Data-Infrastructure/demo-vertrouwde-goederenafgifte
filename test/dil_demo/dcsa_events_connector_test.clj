;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.dcsa-events-connector-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dil-demo.dcsa-events-connector :as sut]
            [nl.jomco.http-status-codes :as http-status])
  (:import java.io.StringReader))

(deftest make-handler
  (let [config  {:portbase-webhook-secret "test"}
        handler (sut/make-handler config)
        req     {:headers        {"content-type" "application/json"}
                 :path-info      "/test"
                 :request-method :post
                 :body           (StringReader. "{}")}]

    (testing "hit"
      (is (= http-status/accepted (-> req (handler) :status))))

    (testing "miss"
      (is (nil? (-> req
                    ;; wrong location
                    (assoc :path-info "/wrong")
                    (handler))))
      (is (nil? (-> req
                    ;; wrong content type
                    (assoc-in [:headers "content-type"] "wrong")
                    (handler))))
      (is (nil? (-> req
                    ;; no content type
                    (update :headers dissoc "content-type")
                    (handler))))
      (is (nil? (-> req
                    ;; wrong method
                    (assoc :request-method :put)
                    (handler)))))))

(defn- read-event [f]
  (-> f (io/resource) (io/reader) (json/read)))

(def example-equipment-gate-in
  (read-event "test/portbase/example_DCSA_external_equipmentGatedIn.json"))

(def example-equipment-loaded
  (read-event "test/portbase/example_DCSA_external_equipmentLoaded.json"))

(def example-transport-departed
  (read-event "test/portbase/example_DCSA_external_transportDeparted.json"))

(def example-container-nr ;; as defined in example files
  "APZU4812090")

(def example-port-visit-ref ;; as defined in example files
  "NLRTM24000001")

(deftest apply-event
  (let [test-container-nr "BICU123457"

        state (-> {}
                  (sut/add-container-ref ["first" test-container-nr])
                  (sut/add-container-ref ["second" example-container-nr])
                  (sut/add-container-ref ["third" example-container-nr]))]
    (is (= {:container-nr-order-refs {"BICU123457"         #{"first"}
                                      example-container-nr #{"second" "third"}}}
           state)
        "added some containers")

    (is (= state
           (-> state
               (sut/apply-event example-equipment-gate-in)))
        "gate-in has no effect on state")

    (is (= {example-port-visit-ref #{example-container-nr}}
           (-> state
               (sut/apply-event example-equipment-gate-in)
               (sut/apply-event example-equipment-loaded)
               :port-visit-ref-container-nrs))
        "loaded couples container with port visit of transport")

    (is (= {:port-visit-ref-container-nrs {}
            :container-nr-order-refs            {test-container-nr #{"first"}}}
           (-> state
               (sut/apply-event example-equipment-gate-in)
               (sut/apply-event example-equipment-loaded)
               (sut/apply-event example-transport-departed)))
        "departed forgets all about container in example files")))

(deftest dispatch-event
  (testing "nothingness"
    (is (empty? (sut/dispatch-event nil nil nil))
        "no events, no state, nothing")
    (is (empty? (sut/dispatch-event nil nil {:foo "bar"}))
        "unknown event, no state, nothing"))

  (testing "gate-in"
    (is (empty? (sut/dispatch-event nil nil example-equipment-gate-in))
        "no state, nothing")
    (is (= #{["first" example-equipment-gate-in]}
           (-> nil
               (sut/dispatch-event {:container-nr-order-refs {example-container-nr #{"first"}}}
                                   example-equipment-gate-in)
               :dcsa-events-connector/events))
        "one ref registered, one event")
    (is (= #{["first" example-equipment-gate-in]
             ["second" example-equipment-gate-in]}
           (-> nil
               (sut/dispatch-event {:container-nr-order-refs {example-container-nr #{"first" "second"}}}
                                   example-equipment-gate-in)
               :dcsa-events-connector/events))
        "one ref registered, one event"))

  (testing "loaded"
    (is (empty? (sut/dispatch-event nil nil example-equipment-loaded))
        "no state, nothing")
    (is (= #{["first" example-equipment-loaded]}
           (-> nil
               (sut/dispatch-event {:container-nr-order-refs {example-container-nr #{"first"}}}
                                   example-equipment-loaded)
               :dcsa-events-connector/events))
        "one ref registered, one event")
    (is (= #{["first" example-equipment-loaded]
             ["second" example-equipment-loaded]}
           (-> nil
               (sut/dispatch-event {:container-nr-order-refs {example-container-nr #{"first" "second"}}}
                                   example-equipment-loaded)
               :dcsa-events-connector/events))
        "one ref registered, one event"))

  (testing "departed"
    (is (empty? (sut/dispatch-event nil nil example-transport-departed))
        "no state, nothing")
    (is (empty? (-> nil
                    (sut/dispatch-event {:container-nr-order-refs {example-container-nr #{"first"}}}
                                        example-transport-departed)
                    :dcsa-events-connector/events))
        "port visit ref not registered, nothing")
    (is (= #{["first" example-transport-departed]}
           (-> nil
               (sut/dispatch-event {:container-nr-order-refs             {example-container-nr #{"first"}}
                                    :port-visit-ref-container-nrs {example-port-visit-ref #{example-container-nr}}}
                                   example-transport-departed)
               :dcsa-events-connector/events))
        "one ref registered, one event")
    (is (= #{["first" example-transport-departed]
             ["second" example-transport-departed]}
           (-> nil
               (sut/dispatch-event {:container-nr-order-refs {example-container-nr #{"first" "second"}}
                                    :port-visit-ref-container-nrs {example-port-visit-ref #{example-container-nr}}}
                                   example-transport-departed)
               :dcsa-events-connector/events))
        "one ref registered, one event")))

(deftest happy-flow
  (let [state-atom (atom {})]
    ;; add container refs
    (swap! state-atom sut/add-container-ref
           ["first" example-container-nr])
    (swap! state-atom sut/add-container-ref
           ["second" example-container-nr])

    (testing "gate in"
      (is (= #{["first" example-equipment-gate-in]
               ["second" example-equipment-gate-in]}
             (:dcsa-events-connector/events (sut/dispatch-event nil @state-atom example-equipment-gate-in))))
      (swap! state-atom sut/apply-event example-equipment-gate-in))

    (testing "loaded"
      (is (= #{["first" example-equipment-loaded]
               ["second" example-equipment-loaded]}
             (:dcsa-events-connector/events (sut/dispatch-event nil @state-atom example-equipment-loaded))))
      (swap! state-atom sut/apply-event example-equipment-loaded))

    (testing "departed"
      (is (= #{["first" example-transport-departed]
               ["second" example-transport-departed]}
             (:dcsa-events-connector/events (sut/dispatch-event nil @state-atom example-transport-departed))))
      (swap! state-atom sut/apply-event example-transport-departed))))

(deftest wrap-container-register
  (let [handler (sut/wrap-container-register identity)]
    (is (nil? (handler nil))
        "nothing in, nothing out")
    (is (= [[:assoc! :dcsa-events-connector {:container-nr-order-refs {'container #{'ref}}}]]
           (-> {:dcsa-events-connector/container-nr-order-refs [['ref 'container]]}
               (handler)
               :store/commands))
        "add assoc! for container-nr-ref")
    (is (= [[:other-command]
            [:assoc! :dcsa-events-connector {:container-nr-order-refs {'container #{'ref}}}]]
           (-> {:dcsa-events-connector/container-nr-order-refs [['ref 'container]]
                :store/commands              [[:other-command]]}
               (handler)
               :store/commands))
        "add assoc! for container-nr-ref"))

  (deftest wrap-event-handler
    (let [handler (sut/wrap-event-handler identity)]
      (is (nil? (handler nil))
          "nothing in, nothing out")
      (testing "gate in"
        (is (nil? (-> {::sut/event example-equipment-gate-in}
                      (handler)
                      :dcsa-events-connector/events))
            "nothing for an unregistered container")
        (is (= #{['order-ref
                  example-equipment-gate-in]}
               (-> {:store      {:dcsa-events-connector {:container-nr-order-refs {example-container-nr #{'order-ref}}}}
                    ::sut/event example-equipment-gate-in}
                   (handler)
                   :dcsa-events-connector/events))
            "pass a gate in event for registered container"))

      (testing "loaded"
        (is (nil? (-> {::sut/event example-equipment-loaded}
                      (handler)
                      :dcsa-events-connector/events))
            "nothing for an unregistered container")
        (is (= #{['order-ref
                  example-equipment-loaded]}
               (-> {:store      {:dcsa-events-connector {:container-nr-order-refs {example-container-nr #{'order-ref}}}}
                    ::sut/event example-equipment-loaded}
                   (handler)
                   :dcsa-events-connector/events))
            "pass a loaded event for registered container")
        (is (= [[:assoc! :dcsa-events-connector {:container-nr-order-refs            {example-container-nr #{'order-ref}}
                                                 :port-visit-ref-container-nrs {example-port-visit-ref #{example-container-nr}}}]]
               (-> {:store      {:dcsa-events-connector {:container-nr-order-refs {example-container-nr #{'order-ref}}}}
                    ::sut/event example-equipment-loaded}
                   (handler)
                   :store/commands))
            "record port-visit-ref"))

      (testing "departed"
        (is (nil? (-> {:store      {:dcsa-events-connector {:container-nr-order-refs {example-container-nr #{'order-ref}}}}
                       ::sut/event example-transport-departed}
                      (handler)
                      :dcsa-events-connector/events))
            "nothing for an unregistered port visit ref")
        (is (= #{['order-ref
                  example-transport-departed]}
               (-> {:store      {:dcsa-events-connector {:container-nr-order-refs            {example-container-nr #{'order-ref}}
                                                         :port-visit-ref-container-nrs {example-port-visit-ref #{example-container-nr}}}}
                    ::sut/event example-transport-departed}
                   (handler)
                   :dcsa-events-connector/events))
            "pass a departed event for registered container")
        (is (= [[:assoc! :dcsa-events-connector {:container-nr-order-refs            {}
                                                 :port-visit-ref-container-nrs {}}]]
               (-> {:store      {:dcsa-events-connector {:container-nr-order-refs            {example-container-nr #{'order-ref}}
                                                         :port-visit-ref-container-nrs {example-port-visit-ref #{example-container-nr}}}}
                    ::sut/event example-transport-departed}
                   (handler)
                   :store/commands))
            "drop container for record")))))
