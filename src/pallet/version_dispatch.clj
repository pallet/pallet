(ns pallet.version-dispatch
  "Dispatch that is version aware.

A version is a dotted string, eg. \"1.0.3\", which is represented as a vector
`[1 0 3]`.

A version specification is either a version vector, which matches a single
version (and all point versions thereof), or a vector of two elements,
specifying an inclusive version range. A nil in the version vector signifies an
open end to the range.

The basic idea is that you wish to dispatch on hierarchy where the dispatched
data may provide a version."
  (:require
   [clojure.string :as string])
  (:use
   [pallet.compute :only [os-hierarchy]]
   [pallet.monad :only [phase-pipeline let-s]]
   [pallet.core.session :only [os-family os-version]]
   [pallet.versions
    :only [as-version-vector version-less version-matches? version-spec-less]]
   [slingshot.slingshot :only [throw+]]))

(defn ^{:internal true} hierarchy-vals
  "Returns all values in a hierarchy, whether parents or children."
  [hierarchy]
  (set
   (concat
    (keys (:parents hierarchy))
    (keys (:descendants hierarchy)))))

(defn ^{:internal true} match-less
  [hierarchy [i _] [j _]]
  (let [osi (:os i)
        os-versioni (:os-version i)
        versioni (:version i)
        osj (:os j)
        os-versionj (:os-version j)
        versionj (:version j)]
    (cond
      (and (isa? hierarchy osi osj) (not (isa? hierarchy osj osi))) true
      (and (isa? hierarchy osj osi) (not (isa? hierarchy osi osj))) false
      (version-spec-less os-versioni os-versionj) true
      (version-spec-less os-versionj os-versioni) false
      :else (version-spec-less versioni versionj))))

(defn ^{:internal true} dispatch-version
  [sym os os-version version args hierarchy methods]
  (letfn [(matches? [[i _]]
            (and (isa? hierarchy os (:os i))
                 (version-matches? os-version (:os-version i))
                 (version-matches? version (:version i))))]
    (if-let [[_ f] (first (sort
                           (comparator (partial match-less hierarchy))
                           (filter matches? methods)))]
      (apply f os os-version version args)
      (if-let [f (:default methods)]
        (apply f os os-version version args)
        (throw+
         {:reason :defmulti-version-method-missing
          :multi-version sym
          :os os
          :os-version os-version
          :version version}
         "No %s method for :os %s :os-version %s :version %s"
         sym os os-version version)))))

(defmacro defmulti-version
  "Defines a multi-version funtion used to abstract over an operating system
hierarchy, where dispatch includes an optional `os-version`. The `version`
refers to a software package version of some sort, on the specified `os` and
`os-version`."
  {:indent 2}
  [name [os os-version version & args] hierarchy-place]
  `(do
     (let [h# ~hierarchy-place
           m# (atom {})]
       (defn ~name
         {:hierarchy h# :methods m#}
         [~os ~os-version ~version ~@args]
         (dispatch-version '~name
          ~os ~os-version ~version [~@args] (var-get h#) @m#)))))

(defmacro defmethod-version
  "Adds a method to the specified multi-version function for the specified
`dispatch-value`."
  {:indent 3}
  [multi-version {:keys [os os-version version] :as dispatch-value}
   [& args] & body]
  (let [{:keys [hierarchy methods]} (meta (resolve multi-version))
        h (var-get hierarchy)]
    (when-not ((hierarchy-vals h) os)
      (throw (Exception. (str os " is not part of the hierarchy"))))
    `(swap! (:methods (meta (var ~multi-version))) assoc ~dispatch-value
            (fn
              ~(symbol
                (str (name os) "-" os-version "-" (string/join "" version)))
              [~@args]
              ~@body))))

(defmacro defmulti-version-plan
  "Defines a multi-version funtion used to abstract over an operating system
hierarchy, where dispatch includes an optional `os-version`. The `version`
refers to a software package version of some sort, on the specified `os` and
`os-version`."
  {:indent 2}
  [name [version & args]]
  `(do
     (let [h# #'os-hierarchy
           m# (atom {})]
       (defn ~name
         {:hierarchy h# :methods m#}
         [~version ~@args]
         (fn [session#]
           ((dispatch-version
              '~name
              (os-family session#)
              (as-version-vector (os-version session#))
              (as-version-vector ~version) [~@args] (var-get h#) @m#)
            session#))))))

(defmacro defmethod-version-plan
  "Adds a method to the specified multi-version function for the specified
`dispatch-value`."
  {:indent 3}
  [multi-version {:keys [os os-version version] :as dispatch-value}
   [& args] & body]
  (let [{:keys [hierarchy methods]} (meta (resolve multi-version))
        h (var-get hierarchy)]
    (when-not ((hierarchy-vals h) os)
      (throw (Exception. (str os " is not part of the hierarchy"))))
    `(swap! (:methods (meta (var ~multi-version))) assoc ~dispatch-value
            (fn ~(symbol
                  (str (name os) "-" os-version "-" (string/join "" version)))
              [~@args]
              (phase-pipeline
                  ~(symbol
                    (str (name os) "-" os-version "-" (string/join "" version)))
                  {}
                ~@body)))))

;;; A map that is looked up based on os and os version. The key should be a map
;;; with :os and :os-version keys.
(defn ^{:internal true} lookup-os
  "Pass nil to default-value if non required"
  [os os-version hierarchy values default-value]
  (letfn [(matches? [[i _]]
            (and (isa? hierarchy os (:os i))
                 (version-matches? os-version (:os-version i))))]
    (if-let [[_ v] (first (sort
                           (comparator (partial match-less hierarchy))
                           (filter matches? values)))]
      v
      (if-let [[_ v] (:default values)]
        v
        default-value))))

(declare os-map)

(deftype VersionMap [data]
  clojure.lang.ILookup
  (valAt [m key]
    (lookup-os (:os key) (:os-version key) os-hierarchy data nil))
  (valAt [m key default-value]
    (lookup-os
     (:os key) (:os-version key) os-hierarchy data default-value))
  clojure.lang.IFn
  (invoke [m key]
    (lookup-os (:os key) (:os-version key) os-hierarchy data nil))
  (invoke [m key default-value]
   (lookup-os
     (:os key) (:os-version key) os-hierarchy data default-value))
  clojure.lang.IPersistentMap
  (assoc [m key val]
    (os-map (assoc data key val)))
  (assocEx [m key val]
    (os-map (.assocEx data key val)))
  (without [m key]
    (os-map (.without data key))))

(defn os-map
  "Construct an os version map. The keys should be maps with :os-family
and :os-version keys. The :os-family value should be take from the
`os-hierarchy`. The :os-version should be a version vector, or a version range
vector."
  [{:as os-value-pairs}]
  (VersionMap. os-value-pairs))

(defn os-map-lookup
  [os-map]
  (fn [session]
    [(get os-map {:os (os-family session) :os-version (os-version session)})
     session]))
