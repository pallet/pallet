(ns pallet.resource
  (:require [clojure.contrib.str-utils2 :as string])
  (:use [pallet.target :only [with-target-template with-target-tag]]
        [clojure.contrib.def :only [name-with-attributes]]))

(def required-resources (atom []))

(defmacro returning [v & body]
  `(let [return-value# ~v]
     ~@body
     return-value#))

(defn reset-resources
  "Reset the list of resources that should be applied to a target"
  [] (reset! required-resources []))

(defn invoke-resource
  "Handle invocation of a resource.  Invocation should add the args to the
  resources configuration args, and add the resource to the required-resources
  as a [invoke-fn arg-var] tuple."
  [arg-var invoke-fn args]
  (swap! arg-var conj args)
  (swap! required-resources conj [invoke-fn arg-var]))

(defn- produce-resource-fn
  "Create a produce funtion for a given resource invoker, binding its arg var
  value.  As a side effect, reset the arg var value."
  [[invoke-fn v]]
  (returning (partial invoke-fn @v)
    (reset! v [])))

(defn configured-resources
  "The currently configured resources"
  []
  (doall (map produce-resource-fn (distinct @required-resources))))

(defmacro defresource
  "defresource is used to define a resource and takes the following arguments:
      [arg-var apply-fn args]

arg-var is a var that will be used to collect the information passed by
multiple invocations of the resource. It should be initialised with (atom []).

apply-fn is a function that will read arg-var and produce a resource.

args is the argument signature for the resource.
"
  [facility & args]
  (let [[facility args] (name-with-attributes facility args)
        [arg-var apply-fn args] args]
    `(do
       (defn ~facility [~@args]
         (invoke-resource
          ~arg-var
          ~apply-fn
          ~(if (some #{'&} args)
                    `(apply vector ~@(filter #(not (= '& %)) args))
                    `[~@args]))))))


(defn output-resources
  "Invoke all accumulated resources."
  [resources]
  (string/join \newline (map #(%) resources)))

(defmacro build-resources
  "Returns a function that outputs the resources specified in the body"
  [& body]
  `(do
     (reset-resources)
     ~@body
     (partial pallet.resource/output-resources
              (pallet.resource/configured-resources))))

(defmacro bootstrap-resources
  "Returns a map that can be used with bootstrap-with."
  [& body]
  `(do
     (reset-resources)
     ~@body
     (let [resources# (pallet.resource/configured-resources)
           f# (partial pallet.resource/output-resources resources#)]
       {:bootstrap-script
        (fn [tag# template#]
          (with-target-template template#
            (with-target-tag tag#
              (f#))))})))

