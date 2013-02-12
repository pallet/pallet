(ns pallet.actions.direct.exec-script-test
  (:use
   clojure.test
   [pallet.build-actions :only [build-actions let-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.actions :only [exec-script* exec-script exec-checked-script exec]]
   [pallet.api :only [group-spec lift server-spec plan-fn]]
   [pallet.core.api :only [phase-errors throw-phase-errors]]
   [pallet.core.user :only [*admin-user*]]
   [pallet.algo.fsmop :only [failed? complete?]]
   [pallet.node :only [hostname]]
   [pallet.node-value :only [node-value]]
   [pallet.script.lib :only [ls]]
   [pallet.script-builder :only [interpreter]]
   [pallet.test-utils
    :only [no-source-line-comments no-location-info script-action test-username
           with-bash-script-language with-location-info]])
  (:require
   pallet.actions.direct.exec-script
   [pallet.compute :as compute]
   [pallet.compute.node-list :as node-list]
   [pallet.stevedore :as stevedore :refer [with-source-line-comments]]
   [pallet.utils :as utils]))

(use-fixtures
 :once
 with-bash-script-language
 (logging-threshold-fixture)
 no-location-info)

(use-fixtures
 :each
 no-source-line-comments)

(defmethod interpreter :python
  [_]
  "/usr/bin/python")

(deftest exec-script*-test
  (let [v (promise)
        rv (let-actions
            {}
            (let [nv (exec-script* "ls file1")]
              (deliver v nv)
              nv))]
    (is (= "ls file1\n" (first rv)))
    (is (= [{:language :bash} "ls file1"] (node-value @v (second rv))))))

(deftest exec-script-test
  (is (= "ls file1\n"
         (first (build-actions {}
                  (exec-script (~ls "file1"))))))
  (is (= "ls file1\nls file2\n"
         (first (build-actions {}
                  (exec-script (~ls "file1") (~ls "file2")))))))


(deftest exec-checked-script-test
  (with-location-info false
    (is (script-no-comment=
         (stevedore/checked-commands
          "check"
          "ls file1")
         (first (build-actions {}
                  (exec-checked-script "check" (~ls "file1"))))))
    (testing "with context"
      (is (script-no-comment=
           (stevedore/checked-commands
            "context\ncheck"
            "ls file1")
           (first
            (build-actions {:phase-context "context"}
              (exec-checked-script "check" (~ls "file1")))))))))

(deftest exec-test
  (let [rv (let-actions
            {}
            (let [nv (exec {:language :python} "print 'Hello, world!'")]
              nv))]
    (is (= "print 'Hello, world!'\n" (first rv)))))

(def print-action
  (script-action [session x]
    [[{:language :python} (str "print '" x "'")] session]))

(deftest lift-all-node-set-test
  (let [local (group-spec
               "local"
               :phases {:configure (plan-fn (print-action "hello"))})
        localhost (node-list/make-localhost-node :group-name "local")
        service (compute/compute-service "node-list" :node-list [localhost])]
    (testing "python"
      (let [session @(lift
                      local
                      :user (assoc *admin-user*
                              :username (test-username) :no-sudo true)
                      :compute service)]
        (is (= ["hello\n"]
               (->>
                session
                :results
                (filter
                 #(and (= "localhost" (hostname (-> % :target :node)))
                       (= :configure (:phase %))))
                (mapcat :result)
                (map :out))))))))

;; this is in the wrong place really, as it is testing phase-fns with arguments
(deftest lift-arguments-test
  (let [localhost (node-list/make-localhost-node :group-name "local")
        service (compute/compute-service "node-list" :node-list [localhost])]
    (testing "simple phase"
      (let [local (group-spec
                   "local"
                   :phases {:configure (fn [x]
                                         (exec-script (println "xx" ~x "yy")))})
            session @(lift
                      local
                      :user (assoc *admin-user*
                              :username (test-username) :no-sudo true)
                      :phase [[:configure "hello"]]
                      :compute service)]
        (is (= ["xx hello yy\n"]
               (->>
                session
                :results
                (filter
                 #(and (= "localhost" (hostname (-> % :target :node)))
                       (= :configure (:phase %))))
                (mapcat :result)
                (map :out))))))
    (testing "compound phase"
      (let [server (server-spec
                    :phases {:configure (fn [x]
                                          (exec-script (println "xx" ~x)))})
            local (group-spec
                   "local"
                   :extends [server]
                   :phases {:configure (fn [x]
                                         (exec-script (println "yy" ~x)))})
            session @(lift
                      local
                      :user (assoc *admin-user*
                              :username (test-username) :no-sudo true)
                      :phase [[:configure "hello"]]
                      :compute service)]
        (is (= ["xx hello\n" "yy hello\n"]
               (->>
                session
                :results
                (filter
                 #(and (= "localhost" (hostname (-> % :target :node)))
                       (= :configure (:phase %))))
                (mapcat :result)
                (map :out))))))))


;; test what happens when a script fails
(deftest lift-fail-test
  (with-source-line-comments true
    (with-location-info true
      (let [localhost (node-list/make-localhost-node :group-name "local")
            service (compute/compute-service
                     "node-list" :node-list [localhost])
            f (fn [x]
                (exec-checked-script
                 "myscript"
                 (println ~x)
                 (file-exists? "abcdef")))]
        (testing "failed script"
          (let [local (group-spec "local" :phases {:configure f})
                result (lift
                        local
                        :user (assoc *admin-user*
                                :username (test-username) :no-sudo true)
                        :phase [[:configure "hello"]]
                        :compute service)
                session @result]
            (is (failed? result))
            (is (phase-errors result))
            (is (thrown? clojure.lang.ExceptionInfo
                 (throw-phase-errors result)))))))))
