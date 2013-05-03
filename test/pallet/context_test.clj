(ns pallet.context-test
  (:require
   [clojure.test :refer :all]
   [clojure.tools.logging :as logging]
   [pallet.common.logging.logutils :as logutils]
   [pallet.context :as context]))

(deftest phase-context-test
  (is (= ["ctx"]
         (context/with-phase-context {:kw :ctxkw :msg "ctx"}
            (context/phase-contexts))))
  (try
   (-> 1
       (context/phase-context
        {:kw :ctxkw :msg "ctx"}
        ((fn [session] (throw (Exception. "msg"))))))
   (catch clojure.lang.ExceptionInfo e
     (is (:context (ex-data e))))))
