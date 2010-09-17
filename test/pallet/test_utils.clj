(ns pallet.test-utils
  (:require
   [pallet.execute :as execute]
   [pallet.target :as target]
   [pallet.script :as script]
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
     `(let [r# (execute/bash ~str)]
       (is (= ~err-msg (:err r#)))
       (is (= ~exit (:exit r#)))
       (:out r#))))

(defn test-username
  "Function to get test username. This is a function to avoid issues with AOT."
  [] (or (. System getProperty "ssh.username")
         (. System getProperty "user.name")))

(def ubuntu-request {:node-type {:image {:os-family :ubuntu}}})
(def centos-request {:node-type {:image {:os-family :centos}}})

(defn with-ubuntu-script-template
  [f]
  (script/with-template [:ubuntu]
    (f)))

;; (defn reset-default-parameters
;;   [f]
;;   (parameter/reset-defaults)
;;   (f))

;; (defn test-resource-build*
;;   "Test build a resource for :configure phase. Ensures binding at correct times.
;;   This assumes no local resources."
;;   [resources node node-type parameters]
;;   (let [{:keys [no-reset-defaults]} parameters]
;;     (when-not no-reset-defaults
;;       (parameter/reset-defaults)))
;;   (apply parameter/default parameters)
;;   (resource/produce-phases
;;    [:configure]
;;    resources
;;    {:all-nodes (filter identity [node])
;;     :target-nodes (filter identity [node])
;;     :target-node node
;;     :node-type node-type
;;     :parameters (parameter/from-default
;;                  [:default
;;                   (target/packager (:image node-type))
;;                   (target/os-family (:image node-type))])}))

;; (defmacro test-resource-build
;;   "Test build a resource for :configure phase.
;;    Ensures binding at correct times."
;;   [[node node-type & parameters] & body]
;;   `(test-resource-build*
;;     (resource/resource-phases ~@body) ~node ~node-type '~parameters))
