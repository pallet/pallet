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
   [clojure.tools.logging :refer [tracef errorf]])
  (:import clojure.lang.IFn
           clojure.lang.Keyword))

;;; ## Basic types
(def bytes? #(= (class (byte-array [])) (class %)))

;;; We use macros so the stack trace reflects the calling location.
(def ^{:dynamic true} *verify-contracts* true)

(defn check-spec* [m spec spec-name line file]
  {:pre [spec]}
  (tracef "check-spec* %s" spec)
  (when *verify-contracts*
    (if-let [errs (seq (validation-errors spec m))]
      (let [e (ex-info
               (format (str "Invalid " spec-name ": %s") (join " " errs))
               {:type :pallet/schema-validation
                :errors errs
                :m m
                :spec spec
                :spec-name spec-name
                :line line
                :file file})]
        (errorf e (str "Invalid " spec-name ":"))
        (doseq [err errs]
          (errorf (str "  " spec-name " error: %s") err))
        (throw e)))
    m))

(defn ^{:requires [validation-errors #'errorf join]} check-spec
  [m spec &form]
  (let [spec-name (string/replace (name spec) "-schema" "")]
    `(check-spec* ~m ~spec ~spec-name ~(:line (meta &form)) ~*file*)))


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
