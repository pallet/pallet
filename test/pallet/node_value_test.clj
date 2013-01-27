(ns pallet.node-value-test
  (:require
   [pallet.node-value :as node-value])
  (:use
   clojure.test))

(deftest node-value-test
  (let [nv (node-value/make-node-value :nvp)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid access.*"
          (node-value/node-value nv {}))
        "throws on deref when not set")
    (let [s (node-value/set-node-value {:current-node-value-path :nvp} 1)]
      (is (= 1 (node-value/node-value nv s))
          "returns the set value when deref'd"))))
