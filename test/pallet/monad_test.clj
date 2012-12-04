(ns pallet.monad-test
  (:use
   clojure.test
   pallet.monad
   pallet.monad.state-accessors
   [pallet.utils :only [with-redef]]))


(deftest let-s-test
  (is (= '(pallet.monad.state-monad/dostate
           [v (pallet.monad.state-accessors/get-state :fred)]
           v)
         (macroexpand-1 `(let-s [~'v (get :fred)] ~'v)))))

(defn with-no-checker
  [f]
  (with-redef [pallet.monad.state-monad/state-checker (constantly nil)]
    (f)))

(use-fixtures :once with-no-checker)

(defn throws [] (fn [s] (throw (Exception. "xx"))))
