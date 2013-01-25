(ns pallet.core.transform-test
  (:require
   [clojure.test :refer :all]
   [pallet.core.transform :refer :all]
   pallet.script
   pallet.stevedore))

(deftest constant-test
  (is (= 1 (eval (plan-emit (plan-fn-ast (ns-name *ns*) 1)))))
  (is (= 'a (eval (plan-emit (plan-fn-ast (ns-name *ns*) '(do 'a))))))
  (is (= ['a] (eval (plan-emit (plan-fn-ast (ns-name *ns*) '(do ['a]))))))
  (is (= {'a 'b}
         (eval (plan-emit (plan-fn-ast (ns-name *ns*) '(do {'a 'b})))))))

(deftest let-test
  (is (= 1 (eval (plan-emit
                  (plan-fn-ast
                   (ns-name *ns*)
                   `(let [x# 1]
                      1)))))))

(deftest named-anonymous-test
  (is (= 1 (eval (plan-emit
                  (plan-fn-ast
                   (ns-name *ns*)
                   `(let [f# (fn ff# []
                               (if nil
                                 (ff#) ; check this resolves
                                 1))]
                      (f#))))))))

(deftest catch-test
  (is (= 1 (eval (plan-emit
                  (plan-fn-ast
                   (ns-name *ns*)
                   `(try
                      1
                      (catch Exception e#
                        (println e#)))))))))

(deftest binding-test
  (is (= "1" (eval (plan-emit
                  (plan-fn-ast
                   (ns-name *ns*)
                   `(binding [*print-readably* true] (pr-str 1))))))))

(deftest case-test
  (is (= 1 (eval (plan-emit
                  (plan-fn-ast
                   (ns-name *ns*)
                   `(let [x# :create]
                      (case x#
                        :create 1
                        :remove 2))))))))

(deftest vector-const-test
  (let [f (plan-emit
           (plan-fn-ast
            (ns-name *ns*)
            '(pallet.stevedore/with-script-language
               :pallet.stevedore.bash/bash
               (pallet.script/with-script-context [:ubuntu]
                 (pallet.stevedore/script
                  (defn pallet_set_env [k v s]
                    (if (not @(grep (quoted @s) "path"))
                      (do
                        (chain-or
                         (chain-and
                          ("sed" -i -e (quoted "/${k}/ d") "path")
                          ("sed" -i -e (quoted "$ a \\\\\n${s}") "path"))
                         (exit 1))))))))))]
    (is (eval f))))

(defn ^:pallet/plan-fn xx [a b] (+ a b))


(deftest node-value-test
  (is (= '(let* [x 1 y (pallet.core.transform-test/xx 1 2)]
            (pallet.core.transform-test/xx
             x (pallet.argument/delayed (clojure.core/deref y)))
            (clojure.core/when x (clojure.core/println x))
            (pallet.action/plan-when-not
             (clojure.core/deref y)
             (pallet.actions/as-action
              (clojure.core/println (clojure.core/deref y)))))
         (-> (plan-fn-ast
              (ns-name *ns*)
              `(let [~'x 1 ~'y (xx 1 2)]
                 (xx ~'x ~'y)
                 (when ~'x (println ~'x))
                 (when-not ~'y (println ~'y))))
             unwrap-ast
             plan-transform
             first
             plan-emit
             ))))
