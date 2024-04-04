(ns dil-demo.ishare.jwt
  "Create, sign and unsign iSHARE JWTs

  See also: https://dev.ishareworks.org/reference/jwt.html"
  (:require [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as keys])
  (:import java.time.Instant
           java.util.UUID
           java.io.StringReader))

(defn- cert-reader
  "Convert base64 encoded certificate string into a reader for parsing
  as a PEM."
  [cert-str]
  ;; TODO: validate cert-str format
  (StringReader. (str "-----BEGIN CERTIFICATE-----\n"
                      cert-str
                      "\n-----END CERTIFICATE-----\n")))

(defn unsign-token
  "Parse a signed token"
  [token]
  {:pre [token]}
  (let [header   (jwt/decode-header token)
        cert-str (first (:x5c header))
        k        (keys/public-key (cert-reader cert-str))]
    (jwt/unsign token k {:alg :rs256 :leeway 5})))

;; From https://dev.ishareworks.org/reference/jwt.html#jwt-header
;;
;;
;;  "Signed JWTs MUST use and specify the RS256 algorithm in the
;;   alg header parameter.
;;
;;   Signed JWTs MUST contain an array of the complete certificate
;;   chain that should be used for validating the JWT’s signature in
;;   the x5c header parameter up until an Issuing CA is listed from
;;   the iSHARE Trusted List.
;;
;;   Certificates MUST be formatted as base64 encoded PEM.
;;
;;   The certificate of the client MUST be the first in the array, the
;;   root certificate MUST be the last.
;;
;;   Except from the alg, typ and x5c parameter, the JWT header SHALL
;;   NOT contain other header parameters."
;;
;;
;; From https://dev.ishareworks.org/reference/jwt.html#jwt-payload
;;
;;   "The JWT payload MUST conform to the private_key_jwt method as
;;    specified in OpenID Connect 1.0 Chapter 9.
;;
;;    The JWT MUST always contain the iat claim.
;;
;;    The iss and sub claims MUST contain the valid iSHARE
;;    identifier (EORI) of the client.
;;
;;    The aud claim MUST contain only the valid iSHARE identifier of
;;    the server. Including multiple audiences creates a risk of
;;    impersonation and is therefore not allowed.
;;
;;    The JWT MUST be set to expire in 30 seconds. The combination of
;;    iat and exp claims MUST reflect that. Both iat and exp MUST be
;;    in seconds, NOT milliseconds. See UTC Time formatting for
;;    requirements.
;;
;;    The JWT MUST contain the jti claim for audit trail purposes. The
;;    jti is not necessary a GUID/UUID.
;;
;;     Depending on the use of the JWT other JWT payload data MAY be
;;     defined."

(defn- seconds-since-unix-epoch
  []
  (.getEpochSecond (Instant/now)))

(defn make-assertion
  [{:ishare/keys [client-id server-id x5c private-key]}]
  {:pre [client-id server-id x5c private-key]}
  (let [iat (seconds-since-unix-epoch)
        exp (+ 30 iat)]
    (jwt/sign {:iss  client-id
               :sub  client-id
               :aud server-id
               :jti (UUID/randomUUID)
               :iat iat
               :exp exp}
              private-key
              {:alg    :rs256
               :header {:x5c x5c
                        :typ "JWT"}})))