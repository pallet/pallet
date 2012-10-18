(ns pallet.monad-test
  (:use
   clojure.test
   pallet.monad
   pallet.monad.state-accessors))

;; (deftest let-s-test
;;   (is (= '(clojure.algo.monads/domonad
;;            pallet.monad/session-m
;;            [v (pallet.monad.state-accessors/get-state :fred)]
;;            v)
;;          (macroexpand-1 `(let-s [~'v (get :fred)] ~'v)))))
