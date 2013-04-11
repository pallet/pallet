(ns pallet.contracts
  "Contracts that can be enforced in pallet code."
  (:require
   [clj-schema.schema :refer [constraints def-map-schema map-schema
                              optional-path predicate-schema seq-schema
                              sequence-of set-of wild]]
   [clj-schema.validation :refer [validation-errors]]
   [clojure.string :refer [join] :as string]
   [clojure.tools.logging :refer [errorf]]
   [pallet.blobstore :refer [blobstore?]]
   [pallet.compute :refer [compute-service?]]))

;;; We put all the contract code here to hide the implementation of the contract
;;; checks.


;;; ## Basic types
(def any-value (constantly true))
(def bytes? #(= (class (byte-array [])) (class %)))


;;; ## Schema types

;;; node-spec contains loose schema, as these vary by, and should be enforced by
;;; the providers.
(def-map-schema image-spec-schema
  :loose
  [(optional-path [:image-id]) [:or string? keyword?]
   (optional-path [:image-description-matches]) string?
   (optional-path [:image-name-matches]) string?
   (optional-path [:image-version-matches]) string?
   (optional-path [:os-family]) keyword?
   (optional-path [:os-64-bit]) any-value
   (optional-path [:os-arch-matches]) string?
   (optional-path [:os-description-matches]) string?
   (optional-path [:os-name-matches]) string?
   (optional-path [:os-version-matches]) string?
   (optional-path [:hypervisor-matches]) string?
   (optional-path [:override-login-user]) string?])

(def-map-schema location-spec-schema
  :loose
  [(optional-path [:location-id]) string?])

(def-map-schema hardware-spec-schema
  :loose
  [(optional-path [:hardware-id]) string?
   (optional-path [:min-ram]) number?
   (optional-path [:min-cores]) number?
   (optional-path [:min-disk]) number?])

(def-map-schema network-spec-schema
  :loose
  [(optional-path [:inbound-ports]) (sequence-of number?)])

(def-map-schema qos-spec-schema
  :loose
  [(optional-path [:spot-price]) number?]
  [(optional-path [:enable-monitoring]) any-value])

(def-map-schema node-spec-schema
  [(optional-path [:image]) image-spec-schema
   (optional-path [:location]) location-spec-schema
   (optional-path [:hardware]) hardware-spec-schema
   (optional-path [:network]) network-spec-schema
   (optional-path [:qos]) qos-spec-schema])

(def-map-schema server-spec-schema
  node-spec-schema
  [(optional-path [:phases]) map?
   (optional-path [:roles]) (set-of keyword?)
   (optional-path [:packager]) keyword?])

(def-map-schema group-spec-schema
  node-spec-schema
  server-spec-schema
  [[:group-name] keyword?
   [:node-filter] fn?
   (optional-path [:count]) number?])

(def-map-schema user-schema
  (constraints
   (fn [{:keys [password private-key-path private-key]}]
     (or password private-key private-key-path)))
  [[:username] string?
   (optional-path [:password]) [:or string? nil?]
   (optional-path [:sudo-password]) [:or string? nil?]
   (optional-path [:no-sudo]) any-value
   (optional-path [:sudo-user]) [:or string? nil?]
   (optional-path [:temp-key]) any-value
   (optional-path [:private-key-path]) [:or string? nil?]
   (optional-path [:public-key-path]) [:or string? nil?]
   (optional-path [:private-key]) [:or string? bytes? nil?]
   (optional-path [:public-key]) [:or string? bytes? nil?]
   (optional-path [:passphrase]) [:or string? bytes? nil?]])

(def-map-schema environment-strict-schema
  [(optional-path [:algorithms]) (map-schema :loose [])
   (optional-path [:user]) user-schema
   (optional-path [:executor]) fn?
   (optional-path [:compute]) compute-service?])

(def phase-with-args-schema
  (seq-schema
   :all
   (constraints (fn [s] (keyword? (first s))))
   any-value))

(def phase-schema
  [:or keyword? fn? phase-with-args-schema])

(def-map-schema lift-options-schema
  environment-strict-schema
  [(optional-path [:compute]) compute-service?
   (optional-path [:blobstore]) [:or nil? blobstore?]
   (optional-path [:phase]) [:or phase-schema (sequence-of phase-schema)]
   (optional-path [:environment]) (map-schema :loose environment-strict-schema)
   (optional-path [:user]) user-schema
   (optional-path [:phase-execution-f]) fn?
   (optional-path [:execution-settings-f]) fn?
   (optional-path [:partition-f]) fn?
   (optional-path [:post-phase-f]) fn?
   (optional-path [:post-phase-fsm]) fn?
   (optional-path [:async]) any-value
   (optional-path [:timeout-ms]) number?
   (optional-path [:timeout-val]) any-value])

(def-map-schema converge-options-schema
  lift-options-schema)

;;; We use macros so the stack trace reflects the calling location.
(def ^{:dynamic true} *verify-contracts* true)

(defn check-spec
  [m spec &form]
  (if *verify-contracts*
    (let [spec-name (string/replace (name spec) "-schema" "")]
      `(let [m# ~m]
         (if-let [errs# (seq (validation-errors ~spec m#))]
           (do
             (errorf ~(str "Invalid " spec-name ":"))
             (doseq [err# errs#]
               (errorf ~(str "  " spec-name " error: %s") err#))
             (throw
              (ex-info
               (format ~(str "Invalid " spec-name ": %s") (join " " errs#))
               {:errors errs#
                :line ~(:line (meta &form))
                :file ~*file*}))))
         m#))
    m))

(defmacro check-node-spec
  [m]
  (check-spec m `node-spec-schema &form))

(defmacro check-server-spec
  [m]
  (check-spec m `server-spec-schema &form))

(defmacro check-group-spec
  [m]
  (check-spec m `group-spec-schema &form))

(defmacro check-user
  [m]
  (check-spec m `user-schema &form))

(defmacro check-lift-options
  [m]
  (check-spec m `lift-options-schema &form))

(defmacro check-converge-options
  [m]
  (check-spec m `converge-options-schema &form))
