(ns pallet.environment-test
  (:require
   [pallet.environment :as environment]
   [clojure.java.io :as io])
  (:use
   clojure.test))

(deftest merge-environments-test
  (testing "single argument"
    (is (= {:a 1} (environment/merge-environments {:a 1}))))
  (testing "defalut algorithm"
    (is (= {:a {:b 1 :c 2}}
           (environment/merge-environments {:a {:c 2}} {:a {:b 1}}))))
  (testing "merge algorithm"
    (is (= {:user {:username "u" :password "p"}}
           (environment/merge-environments
            {:user {:username "u"}}
            {:user {:password "p"}}))))
  (testing "merge middleware"
    (is (= {:middleware :m2}
           (environment/merge-environments
            {:middleware :m1}
            {:middleware :m2}))))
  (testing "merge phases"
    (let [f1 (fn [r] (inc r))
          f2 (fn [r] (* 2 r))]
      (is (= ((->
               (environment/merge-environments
                {:phases {:bootstrap f1}}
                {:phases {:bootstrap f2}})
               :phases :bootstrap) 2) 6))))
  (testing "merge images"
    (is (= {:image {:a :a :b :b}}
         (environment/merge-environments
          {:image {:a :a}}
          {:image {:b :b}})))))

(deftest eval-phase-test
  (testing "with form"
    (is (= [1 2] (#'environment/eval-phase `(vector 1 2))))
    (is (= [1 2] (#'environment/eval-phase (list `vector 1 2)))))
  (testing "with non-form"
    (let [f (fn [])]
      (is (= f (#'environment/eval-phase f))))))

(deftest eval-environment-test
  (testing "no environment"
    (let [env nil]
      (is (nil? (environment/eval-environment env)))))
  (testing "user"
    (let [env {:user {:username "u"}}]
      (is (instance?
           pallet.utils.User
           (:user (environment/eval-environment env))))
      (is (= "u"
             (-> (environment/eval-environment env) :user :username)))))
  (testing "user with shell expand"
    (let [env {:user {:username "u" :public-key-path "~/a"}}]
      (is (instance?
           pallet.utils.User
           (:user (environment/eval-environment env))))
      (is (= (.getAbsolutePath (io/file (System/getProperty "user.home") "a"))
             (-> (environment/eval-environment env) :user :public-key-path)))))
  (testing "arguments"
    (let [env {:algorithms {:lift-fn 'pallet.core/parallel-apply-phase}}
          f (-> (environment/eval-environment env) :algorithms :lift-fn)]
      (is (find-var 'pallet.core/parallel-apply-phase))
      (is (= (var-get (find-var 'pallet.core/parallel-apply-phase)) f)))))

(deftest session-with-environment-test
  (testing "basic merge"
    (is (= {:user {:username :b} :environment {:user {:username :b}}}
           (environment/session-with-environment
             {:user {:username :a}} {:user {:username :b}}))))
  (testing "node-type merge"
    (is (= {:user {:username :b}
            :server {:group-name :t :image :i}
            :environment {:user {:username :b} :groups {:t {:image :i}}}}
           (environment/session-with-environment
             {:user {:username :a} :server {:group-name :t}}
             {:user {:username :b} :groups {:t {:image :i}}}))))
  (testing "phases merge"
    (is (= {:user {:username :b}
            :server {:group-name :t :image :i :phases {:bootstrap identity}}
            :environment {:user {:username :b}
                          :groups {:t {:image :i}}}}
           (environment/session-with-environment
             {:user {:username :a} :server {:group-name :t}}
             {:user {:username :b}
              :groups {:t {:image :i}}
              :phases {:bootstrap identity}}))))
  (testing "user data merge"
    (is (= {:environment {:a {:a :b}}}
           (environment/session-with-environment
             {} {:a {:a :b}})))))
