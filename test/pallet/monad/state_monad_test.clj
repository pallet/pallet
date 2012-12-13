(ns pallet.monad.state-monad-test
  (:use
   clojure.test
   pallet.monad.state-monad
   [pallet.utils :only [with-redef]]))


(defn with-no-checker
  [f]
  (with-redef [pallet.monad.state-monad/state-checker (constantly nil)]
    (f)))

(use-fixtures :once with-no-checker)

;; (def checker @#'pallet.monad.state-monad/state-checker)
;; (check-state-with nil)

(defn minc [x] (fn mincf [s] [(inc x) s]))
(defn s-inc [s] [nil (inc s)])
(defn s-inc-throw [s] (throw (Exception. "xxx")) [nil (inc s)])
(defn minc-throw [] (fn s-inc-throw2 [s]
                      (throw (Exception. "xxx"))
                      [nil (inc s)]))


(deftest dostate-test
  (check-state-with nil)
  (letfn [(mf [x] (dostate
                   [y (minc x)] y))]
    (is (= [2 :state] ((mf 1) :state))))
  (let [f (dostate
           [y s-inc]
           y)]
    (is (= [nil 2] (f 1))))
  (letfn [(f [] (dostate
                 [y s-inc
                  ;; y (minc-throw)
                  ] y
                 ))]
    (is (= [nil 2] ((f) 1))))
  (let [f (dostate
           [y s-inc
            ;; y s-inc-throw
            ]
           y)]
    (is (= [nil 2] (f 1)))))

(deftest when-test
  (check-state-with nil)
  (is (= [nil :state] ((dostate [y (m-when nil (minc 0))] y) :state)))
  (is (= [1 :state] ((dostate [y (m-when true (minc 0))] y) :state))))

(deftest map-test
  (check-state-with nil)
  (letfn [(s-add [x]
            (fn [s]
              ;; (throw (Exception. "xx"))
              [nil (+ s x)]))]
    (is (= [[nil nil nil] 8] ((m-map s-add [1 2 4]) 1)))))

(meta #'s-inc)

;; (check-state-with checker)
