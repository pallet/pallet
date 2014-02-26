(ns pallet.actions-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer :all]
   [pallet.actions.impl :refer [*script-location-info*]]
   [pallet.build-actions :refer [build-plan]]
   [pallet.common.logging.logutils
    :refer [logging-threshold-fixture with-log-to-string]]
   [pallet.plan :refer [plan-fn]]
   [pallet.group :refer [group-spec lift]]
   [pallet.node :refer [primary-ip]]
   [pallet.script.lib :refer [ls]]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils :refer [make-localhost-compute test-username
                              with-location-info]]
   [pallet.user :refer [*admin-user*]]))


(use-fixtures :once (logging-threshold-fixture))

(deftest one-node-filter-test
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]}]
    (is (= {:node 1} (one-node-filter role->nodes [:r]))))
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]
                     :r2 [{:node 2}{:node 3}]}]
    (is (= {:node 2} (one-node-filter role->nodes [:r :r2]))))
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]
                     :r2 [{:node 2}{:node 3}]
                     :r3 [{:node 1}{:node 3}]}]
    (is (= {:node 3} (one-node-filter role->nodes [:r :r2 :r3]))))
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]
                     :r2 [{:node 2}{:node 3}]
                     :r3 [{:node 1}{:node 3}]
                     :r4 [{:node 4}]}]
    (is (= {:node 1} (one-node-filter role->nodes [:r :r2 :r3 :r4])))))

(defn- local-test-user
  []
  (assoc *admin-user* :username (test-username) :no-sudo true))

;; (deftest actions-test
;;   (let [counter (atom 0)
;;         ip (atom 0)
;;         compute (make-localhost-compute :group-name "local")
;;         op (lift
;;             (group-spec "local")
;;             :phase (plan-fn
;;                     (swap! counter inc)
;;                     (swap! counter inc)
;;                     (reset! ip (primary-ip (target-node))))
;;             :compute compute
;;             :user (local-test-user)
;;             :async true)
;;         session @op]
;;     (is (not (phase-errors op)))
;;     (is (= 2 @counter))
;;     (is (= "127.0.0.1" @ip))))

(deftest file-test
  (is (= [{:action 'pallet.actions.decl/file
           :args ["file1" {:action :create}]}]
         (build-plan [session {}]
           (file session "file1"))))
  (is (= [{:action 'pallet.actions.decl/file
           :args ["file1" {:action :create :force true}]}]
         (build-plan [session {}]
           (file session "file1" {:force true})))))


(deftest directory-test
  (is (=
       [{:action 'pallet.actions.decl/directory
         :args ["d1" {:path true
                      :action :create
                      :recursive true
                      :force true}]}]
       (build-plan [session {}]
         (directory session "d1"))))
  (is (=
       [{:action 'pallet.actions.decl/directory
         :args ["d1" {:owner "o"
                      :path true
                      :action :create
                      :recursive true
                      :force true}]}]
       (build-plan [session {}]
         (directory session "d1" {:owner "o"})))))

(deftest directories-test
  (is (=
       [{:action 'pallet.actions.decl/directory
         :args ["d1" {:owner "o"
                      :path true
                      :action :create
                      :recursive true
                      :force true}]}
        {:action 'pallet.actions.decl/directory
         :args ["d2" {:owner "o"
                      :path true
                      :action :create
                      :recursive true
                      :force true}]}]
       (build-plan [session {}]
         (directories session ["d1" "d2"] {:owner "o"})))))

(deftest exec-test
  (is (= [{:action 'pallet.actions.decl/exec,
           :args [{:language :python} "print 'Hello, world!'"]}]
         (build-plan [session {}]
           (exec session {:language :python} "print 'Hello, world!'")))))

(deftest exec-script-test
  (stevedore/with-source-line-comments false
    (is (= [{:action 'pallet.actions.decl/exec-script*
             :args ["ls file1"]}]
           (build-plan [session {}]
             (exec-script session (ls "file1")))))
    (is (= [{:action 'pallet.actions.decl/exec-script*
             :args ["ls file1\nls file2\n"]}]
           (build-plan [session {}]
             (exec-script session (~ls "file1") (~ls "file2")))))))

(alter-var-root #'*script-location-info* (constantly false))
(deftest exec-checked-script-test
  (stevedore/with-source-line-comments false
    (is (= [{:action 'pallet.actions.decl/exec-script*,
             :args
             [(str "echo 'check...';\n{\nls file1\n } || "
                   "{ echo '#> check : FAIL'; exit 1;} >&2 \n"
                   "echo '#> check : SUCCESS'\n")]}]
           (build-plan [session {}]
             (exec-checked-script session "check" (ls "file1"))))))
  ;; (testing "with context"
  ;;   (is (script-no-comment=
  ;;        (stevedore/checked-commands
  ;;         "context: check"
  ;;         "ls file1")
  ;;        (first
  ;;         (build-actions {:phase-context "context"}
  ;;           (exec-checked-script "check" (~ls "file1")))))))
  )
(alter-var-root #'*script-location-info* (constantly true))


(deftest remote-file-test
  (testing "non-existant local-file"
    (is (thrown-cause-with-msg?
         Exception #".*/some/non-existing/file.*does not exist, is a .*"
         (build-plan [session {}]
           (remote-file session
                        "file1" {:local-file "/some/non-existing/file"})))))
  (testing "no content specified"
    (with-log-to-string []
      (is (thrown-cause-with-msg?
           Exception #".*Constraint failed.*"
           (->
            (build-plan [session {}]
              (remote-file session "file1" {:owner "user1"}))))))))
