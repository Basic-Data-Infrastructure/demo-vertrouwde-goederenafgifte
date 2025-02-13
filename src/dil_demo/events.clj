;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.events
  (:require [dil-demo.config :refer [->site-config]]
            [dil-demo.erp.events :as erp]
            [dil-demo.events.pulsar :as events.pulsar]
            [dil-demo.store :as store]
            [dil-demo.tms.events :as tms]
            [nl.jomco.resources :as resources]))

(defn make-site-handler
  [site-id config handler]
  (let [config (->site-config config site-id)]
    (-> (fn site-handler [req]
          (when (= site-id (:site-id req))
            (handler req)))
        (events.pulsar/wrap-fetch-and-store-event config)
        (store/wrap-truncate :pulses config)
        (store/wrap config)
        (events.pulsar/wrap-exec-commands config))))

(defn- resubscribe! [config]
  (doseq [[config cmds] (->> {:erp erp/resubscribe-commands
                              :tms-1 tms/resubscribe-commands
                              :tms-2 tms/resubscribe-commands}
                             (map (fn [[site-id f]]
                                    (let [config (->site-config config site-id)]
                                      [config (f config)]))))]
    (doseq [cmd cmds] (events.pulsar/exec! cmd config))))

(defn make-resource
  [{:keys [pulsar] :as config}]
  (let [events (resources/closeable
                {:handler (let [handlers [(make-site-handler :erp config erp/handler)
                                          (make-site-handler :tms-1 config tms/handler)
                                          (make-site-handler :tms-2 config tms/handler)]]
                            (fn handler [req]
                              (some #(% req) handlers)))
                 :pulsar  pulsar}
                (fn [_] (events.pulsar/close-websockets!)))]
    (resubscribe! (assoc-in config [:resources :events] events))

    events))
