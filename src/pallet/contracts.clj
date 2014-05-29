(ns pallet.contracts
  "Contracts that can be enforced in pallet code."
  (:require
   [clj-schema.schema
    :refer [constraints
            def-map-schema
            map-schema
            optional-path
            seq-schema
            sequence-of
            set-of
            wild]]
   [clj-schema.validation :refer [validation-errors]]
   [clojure.string :as string]
   [clojure.string :refer [join]]
   [clojure.tools.logging :refer [tracef errorf]]
   [pallet.blobstore :refer [blobstore?]]
   [pallet.compute :refer [compute-service?]])
  (:import clojure.lang.IFn
           clojure.lang.Keyword))

;;; We put all the contract code here to hide the implementation of the contract
;;; checks.


;;; ## Basic types
(def any-value (constantly true))
(def bytes? #(= (class (byte-array [])) (class %)))


;;; ## Schema types

;;; node-spec contains loose schema, as these vary by, and should be enforced by
;;; the providers.
(def-map-schema :loose image-spec-schema
  [(optional-path [:image-id]) [:or String Keyword]
   (optional-path [:image-description-matches]) String
   (optional-path [:image-name-matches]) String
   (optional-path [:image-version-matches]) String
   (optional-path [:os-family]) Keyword
   (optional-path [:os-64-bit]) any-value
   (optional-path [:os-arch-matches]) String
   (optional-path [:os-description-matches]) String
   (optional-path [:os-name-matches]) String
   (optional-path [:os-version-matches]) String
   (optional-path [:hypervisor-matches]) String
   (optional-path [:override-login-user]) String])

(def-map-schema :loose location-spec-schema
  [(optional-path [:location-id]) String])

(def-map-schema :loose hardware-spec-schema
  [(optional-path [:hardware-id]) String
   (optional-path [:min-ram]) Number
   (optional-path [:min-cores]) Number
   (optional-path [:min-disk]) Number])

(def-map-schema inbound-port-spec-schema
  [[:start-port] Number
   (optional-path [:end-port]) Number
   (optional-path [:protocol]) String])

(def inbound-port-schema
  [:or inbound-port-spec-schema Number])

(def-map-schema :loose network-spec-schema
  [(optional-path [:inbound-ports]) (sequence-of inbound-port-schema)])

(def-map-schema :loose qos-spec-schema
  [(optional-path [:spot-price]) Number
   (optional-path [:enable-monitoring]) any-value])

(def-map-schema node-spec-schema
  [(optional-path [:image]) image-spec-schema
   (optional-path [:location]) location-spec-schema
   (optional-path [:hardware]) hardware-spec-schema
   (optional-path [:network]) network-spec-schema
   (optional-path [:qos]) qos-spec-schema
   (optional-path [:provider]) (map-schema :loose [])])

(def-map-schema phases-schema
  [[(wild Keyword)] IFn])

(def-map-schema phase-meta-schema
  [(optional-path [:phase-execution-f]) IFn
   (optional-path [:execution-settings-f]) IFn
   (optional-path [:post-phase-f]) IFn
   (optional-path [:post-phase-fsm]) IFn])

(def-map-schema phases-meta-schema
  [[(wild Keyword)] phase-meta-schema])

(def-map-schema server-spec-schema
  node-spec-schema
  [(optional-path [:phases]) phases-schema
   (optional-path [:roles]) (set-of Keyword)
   (optional-path [:packager]) Keyword
   (optional-path [:phases-meta]) phases-meta-schema
   (optional-path [:default-phases]) (sequence-of Keyword)])

(def-map-schema group-spec-schema
  node-spec-schema
  server-spec-schema
  [[:group-name] Keyword
   (optional-path [:node-filter]) IFn
   (optional-path [:count]) Number])

(def-map-schema user-schema
  (constraints
   (fn [{:keys [password private-key-path private-key]}]
     (or password private-key private-key-path)))
  [[:username] String
   (optional-path [:password]) [:or String nil]
   (optional-path [:sudo-password]) [:or String nil]
   (optional-path [:no-sudo]) any-value
   (optional-path [:sudo-user]) [:or String nil]
   (optional-path [:temp-key]) any-value
   (optional-path [:private-key-path]) [:or String nil]
   (optional-path [:public-key-path]) [:or String nil]
   (optional-path [:private-key]) [:or String bytes? nil]
   (optional-path [:public-key]) [:or String bytes? nil]
   (optional-path [:passphrase]) [:or String bytes? nil]
   (optional-path [:state-root]) [:or String nil]
   (optional-path [:state-group]) [:or String nil]])

(def-map-schema environment-strict-schema
  [(optional-path [:algorithms]) (map-schema :loose [])
   (optional-path [:user]) user-schema
   (optional-path [:executor]) IFn
   (optional-path [:compute]) [:or compute-service? nil]])

(def phase-with-args-schema
  (seq-schema
   :all
   (constraints (fn [s] (keyword? (first s))))
   any-value))

(def phase-schema
  [:or Keyword IFn phase-with-args-schema])

(def-map-schema lift-options-schema
  environment-strict-schema
  [(optional-path [:compute]) [:or compute-service? nil]
   (optional-path [:blobstore]) [:or nil blobstore?]
   (optional-path [:phase]) [:or phase-schema (sequence-of phase-schema)]
   (optional-path [:environment]) (map-schema :loose environment-strict-schema)
   (optional-path [:user]) user-schema
   (optional-path [:consider-groups]) (sequence-of group-spec-schema)
   (optional-path [:phase-execution-f]) IFn
   (optional-path [:execution-settings-f]) IFn
   (optional-path [:partition-f]) IFn
   (optional-path [:post-phase-f]) IFn
   (optional-path [:post-phase-fsm]) IFn
   (optional-path [:async]) any-value
   (optional-path [:timeout-ms]) Number
   (optional-path [:timeout-val]) any-value
   (optional-path [:debug :script-comments]) any-value
   (optional-path [:debug :script-trace]) any-value
   (optional-path [:os-detect]) any-value
   (optional-path [:all-node-set]) set?
   (optional-path [:plan-state]) any-value])

(def-map-schema converge-options-schema
  lift-options-schema
  [[:compute] compute-service?])

;;; We use macros so the stack trace reflects the calling location.
(def ^{:dynamic true} *verify-contracts* true)

(defn check-spec* [m spec spec-name line file]
  {:pre [spec]}
  (tracef "check-spec* %s" spec)
  (when *verify-contracts*
    (if-let [errs (seq (validation-errors spec m))]
      (do
        (errorf (str "Invalid " spec-name ":"))
        (doseq [err errs]
          (errorf (str "  " spec-name " error: %s") err))
        (throw
         (ex-info
          (format (str "Invalid " spec-name ": %s") (join " " errs))
          {:type :pallet/schema-validation
           :errors errs
           :m m
           :spec spec
           :spec-name spec-name
           :line line
           :file file}))))
    m))

(defn ^{:requires [validation-errors #'errorf join]} check-spec
  [m spec &form]
  (let [spec-name (string/replace (name spec) "-schema" "")]
    `(check-spec* ~m ~spec ~spec-name ~(:line (meta &form)) ~*file*)))

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

(defn check-keys*
  [m keys spec msg &form]
  `(when *verify-contracts*
     (let [m# (select-keys ~m ~keys)
           spec# ~spec
           msg# ~msg]
       (if-let [errs# (seq (validation-errors spec# m#))]
         (do
           (errorf (str "Invalid " msg#  ":"))
           (doseq [err# errs#]
             (errorf (str "  " msg# " error: %s") err#))
           (throw
            (ex-info
             (format (str "Invalid " msg# ": %s") (join " " errs#))
             {:errors errs#
              :line ~(:line (meta &form))
              :file ~*file*}))))
       m#)))

(defmacro check-keys
  "Check keys in m"
  [m keys spec msg]
  (check-keys* m keys spec msg &form))
