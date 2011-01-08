(ns pallet.script
  "Base infrastructure for script generation, and operating system abstraction.

   `defscript` defines an abstract script function, that can be implemented
   for different operating system (and version) targets.

   `implement` is used to add an implementation to a function defined with
   `defscript`.

   Dispatch of `defscript` is based on an implementation's specialisers vector.
   All elements of the specialisers vector must match `*template*`.  The
   specialisers vector may contain keywords that match if the are in
   `*template*`, sets of keywords that match if any of the set values are in
   `*template*`, or functions that will be passed `*template*` as an argument
   and should return a truth value indicating whether a match occured.

   Mutiple implementations may match the `*template*` vector, and the
   best fit is determined by the highest number of matching specialiser
   functions, with ties decided by the earliest defined implementation."
  (:use clojure.contrib.logging)
  (:require
   [clojure.contrib.def :as def]
   [clojure.contrib.condition :as condition]))

;; map from script name to implementations
;; where implementations is a map from keywords to function
(defonce *scripts* {})

(def *script-line* nil)
(def *script-file* nil)

(def/defunbound *template*
  "Determine the target to generate script for.
   `defscript` implementations are dispatched on this.  The value should
   be a vector, containing os-family values (e.g. `:ubuntu`), os-family and
   os-version values (e.g. `:centos-5.3`), or other keywords.")

(defmacro with-template
  "Specify the target for script generation. `template` should be a vector of
   os-family, os-family and os-version, or other keywords. "
  [template & body]
  `(binding [*template* (filter identity ~template)]
     ~@body))

(defmacro with-line-number
  "Record the source line number"
  [& body]
  `(do ;(defvar- ln# nil)
       ;(binding [*script-line* (:line (meta (var ln#)))
       ; *script-file* (:file (meta (var ln#)))]
         (ns-unmap *ns* 'ln#)
         ~@body));)

(defn- print-args
  "Utitlity function to print arguments for logging"
  [args]
  (str "(" (apply str (interpose " " args)) ")"))

(defn- match-fn
  "Determines if a given `defscript` implementation specialiser matches
   `*template*`.
     - A keyword specialiser matches if the keyword is in `*template*`.
     - A set specialiser matches if any of the `*template*` keywords
       are in the set
     - A function specialiser matches if it returns true when passed
      `*template*`"
  [specialiser]
  (cond
   (keyword? specialiser) (some #(= specialiser %) *template*)
   (set? specialiser) (some #(specialiser %) *template*)
   :else (specialiser *template*)))

(defn- matches?
  "Return the keys that match the template, or nil if any of the keys are not in
   the template."
  [keys]
  (every? match-fn keys))

(defn- more-explicit?
  "Predicate to test whether `candidate` is a more explicit specialiser vector
   than `current`"
  [current candidate]
  (or (= current :default)
      (> (count candidate) (count current))))

(defn- better-match?
  "Predicate to test whether `candidate` is a better matched specialiser vector
   than `current`. `candidate` is first checked to see if it matches the
   current `*template*`"
  [current candidate]
  (if (and (matches? (first candidate))
           (more-explicit? (first current) (first candidate)))
    candidate
    current))

(defn- best-match
  "Determine the best matching implementation of `script` for the current
   `*template*`"
  [script]
  {:pre [*template* (seq *template*)]}
  (trace
   (format "Looking up script %s with template %s" script (seq *template*)))
  (when-let [impls (*scripts* script)]
    (trace (format "Found implementations %s" (keys impls)))
    (second (reduce better-match?
                    [:default (impls :default)]
                    (dissoc impls :default)))))

(defn dispatch-target
  "Invoke `script` with the given `args`.  The implementations of `script` is
   found based on the current `*template*` value.  If no matching
   implementation is found, then a :no-script-implementation condition
   is raised."
  [script & args]
  (trace (str "dispatch-target " script " " (print-args ~@args)))
  (let [f (best-match script)]
    (if f
      (apply f args)
      (condition/raise
       :type :no-script-implementation
       :template *template*
       :message (format
                 "No implementation for %s with template %s"
                 (name script)
                 (pr-str *template*))))))

(defn invoke-target
  "Invoke `script` with the given `args`.  The implementations of `script` is
   found based on the current `*template*` value.  If no matching
   implementation is found, then nil is returned."
  [script args]
  (trace
   (format
    "invoke-target [%s:%s] %s %s"
    *script-file* *script-line* script (print-args args)))
  (when-let [f (best-match (keyword (name script)))]
    (trace
     (format "Found implementation for %s - %s invoking with %s empty? %s"
             script f (print-args args) (empty? args)))
    (apply f args)))

;; TODO - ensure that metadata is correctly placed on the generated function
(defmacro defscript
  "Define an abstract script function, that can be implemented differently for
   different operating systems.
   Calls to functions defined by `defscript` are dispatched based on the
   `*template*` vector."
  [name [& args]]
  (let [fwd-args (filter #(not (= '& %)) args)]
    `(defn ~name [~@args]
       ~(if (seq fwd-args)
          `(apply dispatch-target (keyword (name ~name)) ~@fwd-args)
          `(dispatch-target (keyword (name ~name)))))))


(defn- add-to-scripts
  [scripts script-name specialisers f]
  (assoc scripts script-name
         (assoc (*scripts* script-name {})
           specialisers f)))

(defn implement
  "Add an implementation of script-name for the given specialisers.
   The default implementation can be set by passing :default as the
   `specialisers` argument. `specialisers` should be the :default keyword, or a
   vector.  The `specialisers` vector may contain keywords, a set of keywords
   that provide an inclusive `or` match, or functions that return a truth value
   indication whether the implementation is a match for the template passed as
   the function's first argument."
  [script-name specialisers f]
  (alter-var-root
   #'*scripts*
   (fn add-implementation-fn [current]
     (add-to-scripts current (keyword (name script-name)) specialisers f))))

(defn remove-script
  "Remove all implementations of a script."
  [script-name]
  (alter-var-root
   #'*scripts*
   (fn remove-script-fn [current]
     (dissoc current (keyword (name script-name))))))
