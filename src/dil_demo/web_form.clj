;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.web-form
  (:require [clojure.data.json :as json]
            [dil-demo.i18n :refer [t]]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]])
  (:import (java.util UUID)))

(def ^:dynamic *object* nil)

(defn anti-forgery-input []
  [:input {:name "__anti-forgery-token", :value *anti-forgery-token*, :type "hidden"}])

(defmacro form [object attrs & body]
  `(binding [*object* ~object]
     [:form ~attrs
      (anti-forgery-input)
      ~@body]))

(defn- ks->name [ks]
  (if (vector? ks)
    (reduce (fn [result k] (str (name result) "[" (name k) "]")) ks)
    (name ks)))

(defn- ks->value [ks]
  (str (get-in *object*
               (if (vector? ks) ks [ks]))))

(defn input [ks {:keys [id label list] :as attrs}]
  (let [id      (or id (str "input-" (UUID/randomUUID)))
        list-id (str "list-" (UUID/randomUUID))
        attrs   (if list (assoc attrs :list list-id) attrs)]
    [:div.field
     (when label [:label {:for id} label])
     [:input (-> attrs
                 (dissoc :label)
                 (assoc :id id
                        :name (ks->name ks)
                        :value (ks->value ks)))]
     (when list
       (into [:datalist {:id list-id}]
             (for [v list] [:option {:value v}])))]))

(defn date [ks attrs]
  (input ks (assoc attrs :type "date")))

(defn number [ks attrs]
  (input ks (assoc attrs :type "number")))

(defn text [ks attrs]
  (input ks (assoc attrs :type "text")))

(defn select [ks {:keys [id label list] :as attrs}]
  (let [id (or id (str "id-" (UUID/randomUUID)))]
    [:div.field
     (when label [:label {:for id} label])
     [:div.select-wrapper
      [:select (-> attrs
                   (dissoc :label :list)
                   (assoc :id id
                          :name (ks->name ks)))
       (let [value (ks->value ks)]
         (for [option list]
           (if (vector? option)
             (let [[k v] option]
               [:option {:value k, :selected (= k value)} v])
             [:option {:value option, :selected (= option value)} option])))
       (ks->value ks)]]]))

(defn textarea [ks {:keys [id label] :as attrs}]
  (let [id (or id (str "id-" (UUID/randomUUID)))]
    [:div.field
     (when label [:label {:for id} label])
     [:textarea (-> attrs
                    (dissoc :label)
                    (assoc :id id
                           :name (ks->name ks)))
      (ks->value ks)]]))

(defn confirm-js [& [message]]
  (str "event.stopPropagation(); return confirm(" (json/write-str (or message (t "confirm"))) ")"))

(defn delete-button
  [path & {:keys [label] :or {label (t "button/delete")} :as attrs}]
  (form nil (merge {:method "POST", :action path, :class "form-button"}
                   (:form attrs))
    [:input {:type "hidden", :name "_method", :value "DELETE"}]
    [:button.delete.danger (merge {:onclick (confirm-js)}
                                  (:button attrs))
     label]))

(defn post-button
  [path {:keys [label] :as attrs}]
  {:pre [path label]}
  (form nil (merge {:method "POST", :action path, :class "form-button"}
                   (:form attrs))
    [:button (:button attrs) label]))

(defn submit-button [& [{:keys [label]
                         :or   {label (t "button/save")}
                         :as attrs}]]
  [:button.submit (into {:type "submit"}
                        (dissoc attrs :label))
   label])

(defn cancel-button [& [{:keys [label]
                         :or   {label (t "button/cancel")}
                         :as attrs}]]
  [:a.button.cancel (into {:href "."}
                          (dissoc attrs :label))
   label])

(defn submit-cancel-buttons [& [{:keys [submit cancel]}]]
  [:section.actions
   (cancel-button cancel)
   (submit-button submit)])
