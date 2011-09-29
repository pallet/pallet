(ns pallet.context-test
  (:require
   [clojure.tools.logging :as logging]
   [pallet.context :as context]
   [pallet.common.logging.logutils :as logutils]
   [slingshot.core :as slingshot])
  (:use
   clojure.test))

(deftest phase-context-test
  (is (= "1\n"
         (with-out-str
           (-> 1
               (context/phase-context :ctxkw "ctx" (println))))))
  (is (= "info ctx: 1\n"
         (logutils/logging-to-string
          (-> 1
              (context/phase-context
               :ctxkw "ctx"
               ((fn [session]
                  (context/infof "%s" session))))))))
  (is (= ["ctx"]
           (context/with-phase-context :ctxkw "ctx"
            (context/phase-contexts))))
  (slingshot/try+
   (-> 1
       (context/phase-context
        :ctxkw "ctx"
        ((fn [session] (throw (Exception. "msg"))))))
   (catch map? e
     (is (:context e)))))
