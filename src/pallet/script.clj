(ns pallet.script
  "Base infrastructure for script generation, and operating system abstraction.

   `defscript` defines an abstract script function, that can be implemented
   for different operating system (and version) targets.

   `implement` is used to add an implementation to a function defined with
   `defscript`.

   Dispatch of `defscript` is based on an implementation's specialisers vector.
   All elements of the specialisers vector must match `*script-context*`.  The
   specialisers vector may contain keywords that match if the are in
   `*script-context*`, sets of keywords that match if any of the set values are
   in `*script-context*`, or functions that will be passed `*script-context*` as
   an argument and should return a truth value indicating whether a match
   occured.

   Mutiple implementations may match the `*script-context*` vector, and the
   best fit is determined by the highest number of matching specialiser
   functions, with ties decided by the earliest defined implementation."
  (:require
   [pallet.stevedore :as stevedore]
   [clojure.contrib.def :as def]
   [clojure.contrib.condition :as condition]
   [clojure.contrib.logging :as logging])
  (:use
   [clojure.contrib.core :only [-?>]]))

(def/defunbound *script-context*
  "Determine the target to generate script for.
   `defscript` implementations are dispatched on this.  The value should
   be a vector, containing os-family values (e.g. `:ubuntu`), os-family and
   os-version values (e.g. `:centos-5.3`), or other keywords.")

(defmacro with-script-context
  "Specify the target for script generation. `template` should be a vector of
   os-family, os-family and os-version, or other keywords. "
  [template & body]
  `(binding [*script-context* (filter identity ~template)]
     ~@body))

(defmacro with-template
  "Specify the target for script generation. `template` should be a vector of
   os-family, os-family and os-version, or other keywords.
   DEPRECATED - see `with-script-context`"
  {:deprecated "0.4"}
  [template & body]
  `(with-script-context ~template ~@body))

(defn- print-args
  "Utitlity function to print arguments for logging"
  [args]
  (str "(" (apply str (interpose " " args)) ")"))

(defn- match-fn
  "Determines if a given `defscript` implementation specialiser matches
   `*script-context*`.
     - A keyword specialiser matches if the keyword is in `*script-context*`.
     - A set specialiser matches if any of the `*script-context*` keywords
       are in the set
     - A function specialiser matches if it returns true when passed
      `*script-context*`"
  [specialiser]
  {:pre [*script-context* (seq *script-context*)]}
  (cond
   (keyword? specialiser) (some #(= specialiser %) *script-context*)
   (set? specialiser) (some #(specialiser %) *script-context*)
   :else (specialiser *script-context*)))

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
   current `*script-context*`"
  [current candidate]
  (if (and (matches? (first candidate))
           (more-explicit? (first current) (first candidate)))
    candidate
    current))

(defn- best-match
  "Determine the best matching implementation of `script` for the current
   `*script-context*`"
  [methods]
  (logging/trace
   (format
    "Found implementations %s - template %s"
    (keys methods) (seq *script-context*)))
  (second
   (reduce
    better-match? [:default (methods :default)] (dissoc methods :default))))

(defn dispatch
  "Invoke `script` with the given `args`.  The implementations of `script` is
   found based on the current `*script-context*` value.  If no matching
   implementation is found, then a :no-script-implementation condition
   is raised."
  ([script args]
     (dispatch script args nil nil))
  ([script args file line]
     {:pre [(:methods script)]}
     (logging/trace (str "dispatch-target " script " " (print-args args)))
     (if-let [f (or (best-match @(:methods script)))]
       (apply f args)
       (condition/raise
        :type :no-script-implementation
        :template *script-context*
        :file file
        :line line
        :message (format
                  "No implementation for %s with template %s"
                  (:fn-name script) (pr-str *script-context*))))))

(defn invoke
  "Invoke `script` with the given `args`.  The implementations of `script` is
   found based on the current `*script-context*` value.  If no matching
   implementation is found, then nil is returned."
  ([script args]
     (invoke script args nil nil))
  ([script args file line]
     {:pre [(::script-fn script)]}
     (logging/trace
      (format
       "invoke-target [%s:%s] %s %s"
       file line (or (:kw script) (::script-kw script))
       (print-args args)))
     (when-let [f (best-match @(:methods script))]
       (logging/trace
        (format
         "Found implementation for %s - %s invoking with %s empty? %s"
         (:fn-name script) f (print-args args) (empty? args)))
       (apply f args))))

(defn script-fn*
  "Define an abstract script function, that can be implemented differently for
   different operating systems. Calls to functions defined by `script-fn*` are
   dispatched based on the `*script-context*` vector."
  [fn-name args]
  `(with-meta
     {::script-fn true
      :fn-name ~(keyword (name fn-name))
      :methods (atom {})}
     {:arglists ~(list 'quote (list (vec args)))}))

(defmacro script-fn
  "Define an abstract script function, that can be implemented differently for
   different operating systems. Calls to functions defined by `script-fn` are
   dispatched based on the `*script-context*` vector."
  ([[& args]]
     (script-fn* :anonymous args))
  ([fn-name [& args]]
     (script-fn* fn-name args)))

(defmacro defscript
  "Define a top level var with an abstract script function, that can be
   implemented differently for different operating systems.  Calls to functions
   defined by `defscript` are dispatched based on the `*script-context*`
   vector."
  [fn-name & args]
  (let [[fn-name [args]] (def/name-with-attributes fn-name args)
        fn-name (vary-meta
                 fn-name assoc :arglists (list 'quote (list (vec args))))]
    `(def ~fn-name (script-fn ~fn-name [~@args]))))

(defn implement
  "Add an implementation of script for the given specialisers.
   The default implementation can be set by passing :default as the
   `specialisers` argument. `specialisers` should be the :default keyword, or a
   vector.  The `specialisers` vector may contain keywords, a set of keywords
   that provide an inclusive `or` match, or functions that return a truth value
   indication whether the implementation is a match for the `*script-context*`
   passed as the function's first argument."
  [script specialisers f]
  {:pre [(::script-fn script)]}
  (swap! (:methods script) assoc specialisers f))

;;; Dispatch mechanisms for stevedore

(defmacro defimpl
  "Define a script function implementation for the given `specialisers`.

   `specialisers` should be the :default keyword, or a vector.  The
   `specialisers` vector may contain keywords, a set of keywords that provide an
   inclusive `or` match, or functions that return a truth value indication
   whether the implementation is a match for the script template passed as the
   function's first argument.

   `body` is wrapped in an implicit `script` form.

       (pallet.script/defscript ls [& args])
       (defimpl ls :default [& args] (ls ~@args))
       (defimpl ls [:windows] [& args] (dir ~@args))"
  [script specialisers [& args] & body]
  {:pre [(or (= :default specialisers) (vector? specialisers))]}
  `(implement
    ~script ~specialisers
    (fn [~@args] (stevedore/script ~@body))))

(defn script-fn-dispatch
  "Optional dispatching of script functions"
  [script-fn args ns file line]
  (dispatch script-fn args file line))

;;; Link stevedore to the dispatch mechanism

(stevedore/script-fn-dispatch! script-fn-dispatch)
