(ns pallet.test-utils
  (:require [pallet.utils :as utils])
  (:use clojure.test)
  (:import
   org.jclouds.compute.domain.internal.NodeMetadataImpl
   org.jclouds.compute.domain.NodeState))


(defmacro with-private-vars [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context.  From users mailing
list, Alan Dipert and MeikelBrandmeyer."
  `(let ~(reduce #(conj %1 %2 `@(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

(defn tmpfile []
  (java.io.File/createTempFile "pallet_ssl" "test"))

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
  `(let [r# (utils/bash ~str)]
    (is (= ~err-msg (:err r#)))
    (is (= ~exit (:exit r#)))
    (:out r#))))

