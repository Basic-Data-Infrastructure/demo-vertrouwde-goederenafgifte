;;; SPDX-FileCopyrightText: 2024 Jomco B.V.
;;; SPDX-FileCopyrightText: 2024 Topsector Logistiek
;;; SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
;;; SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
;;;
;;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns dil-demo.http-utils)

(defn parse-www-authenticate
  "Parse given `WWW-Authenticate` header.

  Expects the following format:

    auth-scheme key=\"value\" other=\"thing\"

  into `[\"auth-scheme\" {\"key\" \"value\", \"other\" \"thing\"}]`.

  Note: this is a very basic implementation which expects values to be
  quoted and they can not contain quotes."
  [s]
  (when-let [[_ auth-scheme args] (re-matches #"(?i)([a-z]+)(?:\s+(.+))" s)]
    [auth-scheme (reduce (fn [m [_ k v]] (assoc m k v))
                         {}
                         (re-seq #"\s*(.+?)=\"(.*?)\"" args))]))
