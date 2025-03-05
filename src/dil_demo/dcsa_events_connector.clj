;;; SPDX-FileCopyrightText: 2025 Jomco B.V.
;;; SPDX-FileCopyrightText: 2025 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.dcsa-events-connector
  (:require [clojure.core.match :refer [match]]
            [clojure.core.match.regex :refer []]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [dil-demo.dcsa :as dcsa]
            [nl.jomco.http-status-codes :as http-status]))

(def webhook-path "dcsa-webhook")

(defn make-handler
  "Create a webhook handler.
  This does the translation of portbase events to local events."
  [{:keys [portbase-webhook-secret]}]
  (let [path-info (str "/" portbase-webhook-secret)]
    (fn handler [req]
      (match [req]
        [{:request-method :post
          :path-info      path-info
          :headers        {"content-type" (#"\Aapplication/json\b.*" :guard string?)}}]
        (let [event (-> req :body io/reader json/read)]
          (log/debug "Got event" event)
          {:status http-status/accepted
           ::event event})

        :else
        nil))))



(defmulti apply-event
  "Record state changes due to incoming events."
  (fn [_state event] (dcsa/event-type event)))

(defmethod apply-event ["EQUIPMENT" "LOAD"]
  [state {{equipment-reference "equipmentReference"
           {port-visit-ref "portVisitReference"} "transportCall"} "payload"}]
  ;; record port visit ref for container
  (update-in state
             [:port-visit-ref-container-nrs port-visit-ref]
             (fnil conj #{}) equipment-reference))

(defmethod apply-event ["TRANSPORT" "DEPA"]
  [state {{{port-visit-ref "portVisitReference"} "transportCall"} "payload"}]
  (-> state
      ;; port-visit-ref no long in use
      (update :port-visit-ref-container-nrs dissoc port-visit-ref)
      ;; no expecting any more events for these containers
      (update :container-nr-order-refs
              #(apply dissoc %
                      (get-in state [:port-visit-ref-container-nrs
                                     port-visit-ref])))))

(defmethod apply-event :default
  [state _event]
  state)

(defn add-container-ref
  "Register a container-nr for an order-ref."
  [state [order-ref container-nr]]
  (update-in state [:container-nr-order-refs container-nr]
             (fnil conj #{}) order-ref))



(defn assoc-events [res order-refs event]
  (reduce (fn [res order-ref]
            (update res :dcsa-events-connector/events (fnil conj #{})
                    [order-ref event]))
          res
          order-refs))

(defmulti dispatch-event
  "Dispatch event to related \"local\" order-refs.
   Returns new result with `:dcsa-events-connector/events` having set
  of `[order-ref event]` tuples."
  (fn [_res _state event] (dcsa/event-type event)))

(defmethod dispatch-event ["EQUIPMENT" "GTIN"]
  [res
   {:keys [container-nr-order-refs]
    :as _state}
   {{container-nr "equipmentReference"} "payload"
    :as event}]
  ;; send event to all order-ref related to that container-nr
  (assoc-events res
                (get container-nr-order-refs container-nr)
                event))

(defmethod dispatch-event ["EQUIPMENT" "LOAD"]
  [res
   {:keys [container-nr-order-refs]
    :as _state}
   {{container-nr "equipmentReference"} "payload"
    :as event}]
  ;; send event to all order-ref related to that container-nr
  (assoc-events res
                (get container-nr-order-refs container-nr)
                event))

(defmethod dispatch-event ["TRANSPORT" "DEPA"]
  [res
   {:keys [container-nr-order-refs
           port-visit-ref-container-nrs]
    :as _state}
   {{{port-visit-ref "portVisitReference"} "transportCall"} "payload"
    :as event}]
  ;; send event for all recorded containers on this transport
  (reduce (fn [res c-nr]
            (assoc-events res
                          (get container-nr-order-refs c-nr)
                          event))
          res
          (get port-visit-ref-container-nrs port-visit-ref)))

(defmethod dispatch-event :default
  [res _state event]
  (log/warn "Can not dispatch unknown event" event)
  res)



(defn wrap-container-register
  "Allow adding container-nr with order-ref to container register.

  This middleware handles `:dcsa-events-connector/container-nr-order-refs` which is
  expected to be a sequence of entries containing a `connection-nr`
  and `order-ref`.

  Scope: UI"
  [f]
  (fn container-register-wrapper [{{:keys [dcsa-events-connector]} :store
                                   :as                             req}]
    (let [{:keys [dcsa-events-connector/container-nr-order-refs] :as res} (f req)]
      (cond-> res
        container-nr-order-refs
        (update :store/commands (fnil conj [])
                [:assoc! :dcsa-events-connector
                 (reduce (fn [m r-c] (add-container-ref m r-c))
                         dcsa-events-connector
                         container-nr-order-refs)])))))

(defn wrap-event-handler
  "Hookup connector webhook with consumer.

  This middleware handles events added by the webhook handler in this
  namespace (through `::event`) by updating tracking information (in
  store `dcsa-events-connector` to link events to order-refs).  Tuples
  are added to the store as like `[order-ref event]`.

  Scope: webhook"
  [f]
  (fn event-handler-wrapper [{{:keys [dcsa-events-connector]} :store
                              :as req}]
    (let [{::keys [event] :as res} (f req)]
      (cond-> res
        event
        (-> (dispatch-event dcsa-events-connector event)
            (update :store/commands (fnil conj [])
                    [:assoc! :dcsa-events-connector
                     (apply-event dcsa-events-connector event)]))))))

(defn wrap-webhook-subscription-handler
  "Follow appropriate topics according to state by putting portbase commands on response.

  On equipment-loaded:
  - subscribe to vessel events
  - unsubscribe equipment events

  On transport departed:
  - unsubscribe vessel events

  Scope: webhook"
  [f {:keys [base-url portbase-webhook-secret site-id] :as _config}]
  {:pre [base-url portbase-webhook-secret site-id]}
  (let [callback-url (fn callback-url [user-number]
                       (string/join "/"
                                    [base-url
                                     user-number
                                     (name site-id)
                                     webhook-path
                                     portbase-webhook-secret]))]

    (fn webhook-subscription-handler-wrapper [{:keys [user-number] :as req}]
      {:pre [user-number]}
      (let [{::keys [event] :as res} (f req)]
        (cond-> res
          (dcsa/equipment-loaded? event)
          (update :portbase/commands (fnil into [])
                  [[:subscribe!
                    {:callback-url (callback-url user-number)
                     :vessel-imo-number (dcsa/vessel-imo-number event)}]
                   [:unsubscribe! {:equipment-reference (dcsa/equipment-reference event)}]])

          (dcsa/transport-departed? event)
          (update :portbase/commands (fnil conj [])
                  [:unsubscribe! {:vessel-imo-number (dcsa/vessel-imo-number event)}]))))))
