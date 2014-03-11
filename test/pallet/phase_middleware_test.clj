(ns pallet.phase-middleware-test
  (:require
   [clojure.core.async :refer [<!! chan]]
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.test :refer :all]
   [pallet.phase-middleware :refer :all]
   [pallet.utils.async :refer [channel? go-try]]))

(deftest partition-target-plans-test
  (let [node {:os-family :ubuntu
              :os-version "13.10"
              :packager :apt}
        target1 {:node (assoc node :id "n1")}
        target2 {:node (assoc node :id "n2")}
        partition-f (fn [target-plans]
                      (partition-by (comp :id :node first) target-plans))
        handler (fn [session target-plans ch]
                  (go-try ch
                    (>! ch [[{:target (ffirst target-plans)
                              :count (count target-plans)}]])))
        mw (-> handler
               (partition-target-plans partition-f))
        c (chan)
        result (mw nil [[target1] [target2]] c)
        [res e] (<!! c)
        results [{:target target1 :count 1}{:target target2 :count 1}]]
    (is (not e))
    (when e (print-cause-trace e))
    (is (channel? result))
    (is (= results res))))

(deftest post-phase-test
  (let [a (atom nil)
        results [:result]
        handler (fn [session target-plans ch]
                  (go-try ch
                    (>! ch [results])))
        mw (-> handler
               (post-phase (fn [session targets results]
                             (reset! a results))))
        c (chan)
        result (mw nil nil c)]
    (is (channel? result))
    (is (= [results] (<!! c)))
    (is (= results (first @a)))
    (is (nil? (second @a)) "no exception")))
