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
   [pallet.monad :only [phase-pipeline]]
   [pallet.session :only [os-family* os-version*]]
   [pallet.versions
    :only [as-version-vector version-less version-matches? version-spec-less]]))

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
        (throw (IllegalArgumentException.
                (str "No " sym " method for "
                     os " " os-version " " version)))))))

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

(defmacro multi-version-method
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



(defmacro defmulti-version-crate
  "Defines a multi-version funtion used to abstract over an operating system
hierarchy, where dispatch includes an optional `os-version`. The `version`
refers to a software package version of some sort, on the specified `os` and
`os-version`."
  {:indent 2}
  [name [session version & args]]
  `(do
     (let [h# #'os-hierarchy
           m# (atom {})]
       (defn ~name
         {:hierarchy h# :methods m#}
         [~session ~version ~@args]
         (dispatch-version
          '~name
          (os-family* ~session)
          (as-version-vector (os-version* ~session))
          (as-version-vector ~version) [~@args] (var-get h#) @m#)))))

(defmacro multi-version-crate-method
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
