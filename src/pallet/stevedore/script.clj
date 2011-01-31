(ns pallet.stevedore.script
  "Uses pallet.script to provide script functions in stevedore.

   Script functions (defined with `pallet.script/defscript`) can be implemented
   using `defimpl`."
  (:require
   [pallet.script :as script]
   [pallet.stevedore :as stevedore])
  (:use
   [clojure.contrib.core :only [-?>]]))

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
  `(script/implement
    ~script ~specialisers
    (fn [~@args] (stevedore/script ~@body))))

(defn- as-script
  [x])

(defn- resolve-script-fn
  [ns name]
  (-?> (ns-resolve ns name) var-get))

(defn script-fn-optional-dispatch
  "Optional dispatching of script functions"
  [name args ns file line]
  (when-let [script (resolve-script-fn ns name)]
    (script/invoke script args file line)))

(defn script-fn-mandatory-dispatch
  "Mandatory dispatching of script functions"
  [name args ns file line]
  (when-let [script (resolve-script-fn ns name)]
    (script/dispatch script args file line)))
