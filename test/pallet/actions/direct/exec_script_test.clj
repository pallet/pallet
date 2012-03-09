(ns pallet.actions.direct.exec-script-test
  (:use
   clojure.test
   [pallet.build-actions :only [build-actions let-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.actions :only [exec-script* exec-script exec-checked-script exec]]
   [pallet.node-value :only [node-value]]
   [pallet.script.lib :only [ls]]
   [pallet.script-builder :only [interpreter]]
   [pallet.test-utils
    :only [with-bash-script-language script-action test-username]])
  (:require
   pallet.actions.direct.exec-script
   [pallet.compute :as compute]
   [pallet.compute.node-list :as node-list]
   [pallet.core :as core]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]))

(use-fixtures
 :once
 with-bash-script-language
 (logging-threshold-fixture))

(defmethod interpreter :python
  [_]
  "/usr/bin/python")

(deftest exec-script*-test
  (let [v (promise)
        rv (let-actions {}
             [nv (exec-script* "ls file1")
              _ #(do (deliver v nv) [nv %])]
             nv)]
    (is (= "ls file1" (first rv)))
    (is (= [{:language :bash} "ls file1"] (node-value @v (second rv))))))

(deftest exec-script-test
  (is (= "ls file1"
         (first (build-actions {}
                  (exec-script (~ls "file1"))))))
  (is (= "ls file1\nls file2\n"
         (first (build-actions {}
                  (exec-script (~ls "file1") (~ls "file2")))))))

(deftest exec-checked-script-test
  (is (= (stevedore/checked-commands
          "check"
          "ls file1\n")
         (first (build-actions {}
                  (exec-checked-script "check" (~ls "file1"))))))
  (testing "with context"
    (is (= (stevedore/checked-commands
            "context\ncheck"
            "ls file1\n")
           (first
            (build-actions {:phase-context "context"}
              (exec-checked-script "check" (~ls "file1"))))))))

(deftest exec-test
  (let [rv (let-actions {}
             [nv (exec {:language :python} "print 'Hello, world!'")]
             nv)]
    (is (= "[{:language :python} \"print 'Hello, world!'\"]" (first rv)))))

(def print-action
  (script-action [session x]
    [[{:language :python} (str "print '" x "'")] session]))

(deftest lift-all-node-set-test
  (let [local (core/group-spec
               "local"
               :phases {:configure (print-action "hello")})
        localhost (node-list/make-localhost-node :group-name "local")
        service (compute/compute-service "node-list" :node-list [localhost])]
    (testing "python"
      (let [session (core/lift
                     local
                     :user (assoc utils/*admin-user*
                             :username (test-username) :no-sudo true)
                     :compute service)]
        (is (= "hello\n"
               (-> session :results :localhost :configure first :out)))))))
