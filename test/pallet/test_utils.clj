(ns pallet.test-utils
  (:require
   [pallet.execute :as execute]
   [pallet.target :as target]
   [pallet.script :as script]
   [pallet.resource :as resource]
   [pallet.resource-build :as resource-build]
   [pallet.parameter :as parameter]
   [pallet.compute.node-list :as node-list]
   [pallet.utils :as utils])
  (:use clojure.test))

(defmacro with-private-vars [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context.  From users mailing
list, Alan Dipert and MeikelBrandmeyer."
  `(let ~(reduce #(conj %1 %2 `@(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

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

(defn make-node
  "Simple node for testing"
  [tag & {:as options}]
  (apply node-list/make-node
   tag (:tag options tag) (:ip options "1.2.3.4") (:os-family options :ubuntu)
   (apply concat options)))

(defmacro build-resources
  "Forwarding definition, until resource-when is fixed"
  [& args]
  `(resource-build/build-resources ~@args))
