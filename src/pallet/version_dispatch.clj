(ns pallet.version-dispatch
  "Dispatch that is version aware.

A version is a dotted string, e.g. \"1.0.3\", which is represented as a vector
`[1 0 3]`.

A version specification is either a version vector, which matches a single
version (and all point versions thereof), or a vector of two elements,
specifying an inclusive version range. A nil in the version vector signifies an
open end to the range.

The basic idea is that you wish to dispatch on hierarchy where the dispatched
data may provide a version."
  (:require
   [clojure.string :as string]
   [pallet.compute :refer [os-hierarchy]]
   [pallet.versions :refer [version-spec?]]
   [pallet.core.version-dispatch
    :refer [os-match-less version-spec-more-specific version-map]]
   [pallet.core.api :refer [phase-context]]
   [pallet.core.session :refer [os-family os-version]]
   [pallet.versions :refer [as-version-vector version-matches?]]))

(defn ^{:internal true} hierarchy-vals
  "Returns all values in a hierarchy, whether parents or children."
  [hierarchy]
  (set
   (concat
    (keys (:parents hierarchy))
    (keys (:descendants hierarchy)))))

(defn ^{:internal true} dispatch-version
  [sym os os-version version args hierarchy methods]
  (letfn [(matches? [[i _]]
            (and (isa? hierarchy os (:os i))
                 (version-matches? os-version (:os-version i))
                 (version-matches? version (:version i))))]
    (if-let [[_ f] (first (sort
                           (comparator
                            (fn [x y]
                              ((os-match-less hierarchy)
                               (key x) (key y))))
                           (filter matches? methods)))]
      (apply f os os-version version args)
      (if-let [f (:default methods)]
        (apply f os os-version version args)
        (throw
         (ex-info
          (format "No %s method for :os %s :os-version %s :version %s"
                  sym os os-version version)
          {:reason :defmulti-version-method-missing
           :multi-version sym
           :os os
           :os-version os-version
           :version version}))))))

(defmacro defmulti-version
  "Defines a multi-version function used to abstract over an operating system
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
  "Defines a multi-version function used to abstract over an operating system
hierarchy, where dispatch includes an optional `os-version`. The `version`
refers to a software package version of some sort, on the specified `os` and
`os-version`."
  {:indent 2}
  [name [version & args]]
  `(let [h# #'os-hierarchy
         m# (atom {})]
     (defn ~name
       {:hierarchy h# :methods m#}
       [~version ~@args]
       (dispatch-version
        '~name
        (os-family)
        (as-version-vector (os-version))
        (as-version-vector ~version) [~@args] (var-get h#) @m#))))

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
              (phase-context
                  ~(symbol
                    (str (name os) "-" os-version "-" (string/join "" version)))
                  {}
                ~@body)))))

(defn os-map
  "Construct an os version map. The keys should be maps with :os-family
and :os-version keys. The :os-family value should be take from the
`os-hierarchy`. The :os-version should be a version vector, or a version range
vector."
  [{:as os-value-pairs}]
  (version-map os-hierarchy :os :os-version os-value-pairs))

(defn os-map-lookup
  [os-map]
  (get os-map {:os (os-family) :os-version (as-version-vector (os-version))}))
