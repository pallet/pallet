(ns pallet.test-utils
  (:require
   [pallet.target :as target]
   [pallet.resource :as resource]
   [pallet.parameter :as parameter]
   [pallet.utils :as utils])
  (:use clojure.test)
  (:import
   org.jclouds.compute.domain.internal.NodeMetadataImpl
   org.jclouds.compute.domain.NodeState))

(defmacro with-private-vars [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context.  From users mailing
list, Alan Dipert and MeikelBrandmeyer."
  `(let ~(reduce #(conj %1 %2 `@(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

(defn tmpfile
  "Create a temporary file"
  ([] (java.io.File/createTempFile "pallet_" "test"))
  ([^java.io.File dir] (java.io.File/createTempFile "pallet_" "test" dir)))

(defn tmpdir []
  (doto (java.io.File/createTempFile "pallet_" "test")
    (.delete)
    (.mkdir)))

(defmacro with-temporary
  [bindings & body]
  {:pre [(vector? bindings)
         (even? (count bindings))]}
  (cond
   (= (count bindings) 0) `(do ~@body)
   (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                             (try
                              (with-temporary ~(subvec bindings 2) ~@body)
                              (finally
                               (. ~(bindings 0) delete))))
   :else (throw (IllegalArgumentException.
                 "with-temporary only allows Symbols in bindings"))))

(defmacro bash-out
  "Check output of bash. Macro so that errors appear on the correct line."
  ([str] `(bash-out ~str 0 ""))
  ([str exit err-msg]
     `(target/with-local-target
        (let [r# (utils/bash ~str)]
          (is (= ~err-msg (:err r#)))
          (is (= ~exit (:exit r#)))
          (:out r#)))))

(defn with-null-target
  "Bind target to null values"
  [f]
  (target/with-target nil {}
    (f)))

(defn reset-default-parameters
  [f]
  (reset! parameter/default-parameters {})
  (f))

(defmacro test-resource-build
  "Test build a resource for :configure phase. Ensures binding at correct times.
  This assumes no local resources."
  [[node node-type & parameters] & body]
  `(let [resources# (binding [utils/*file-transfers* {}
                              resource/*required-resources* {}]
                      (resource/resource-phases ~@body))]
     (parameter/default ~@parameters)
     (target/with-nodes [~node] [~node]
       (resource/with-target [~node ~node-type]
         (resource/produce-phases [:configure] resources#)))))

(defn parameters-test*
  [& options]
  (let [options (apply hash-map options)]
    (doseq [[[key & keys] value] options]
      (is (= value
             (let [params (get parameter/*parameters* key ::not-set)]
               (is (not (= ::not-set params)))
               (if keys
                 (apply params keys)
                 params)))))
    ""))

(resource/defresource parameters-test
  parameters-test* [& options])
