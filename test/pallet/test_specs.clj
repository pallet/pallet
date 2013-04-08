(ns pallet.test-specs
  "Various group specs to test pallet on live infrastructure."
  (:require
   [clojure.java.io :refer [file]]
   [clojure.string :refer [blank?]]
   [clojure.tools.logging :refer [debugf infof]]
   [pallet.actions
    :refer [as-action assoc-in-settings delete-local-path directory
            exec-checked-script exec-script exec-script* on-one-node remote-file
            rsync-directory]]
   [pallet.algo.fsmop :refer [operate]]
   [pallet.api :refer [group-spec plan-fn] :as api]
   [pallet.core.operations :as ops]
   [pallet.core.primitives :refer [execute-on-unflagged]]
   [pallet.core.plan-state :as ps]
   [pallet.crate
    :refer [admin-user compute-service defplan get-settings target-name
            target-node target-nodes targets]]
   [pallet.node :refer [id tag tag!]]
   [pallet.script-test :refer [testing-script is-true is=]]
   [pallet.utils :refer [tmpdir tmpfile]]))

(def characters (apply vector "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456789"))
(defn rand-char [] (characters (rand-int (count characters))))
(defn rand-str [n] (apply str (take n (repeatedly rand-char))))

(def remote-file-test
  (let [s (rand-str 10)]
    (group-spec "remote-file"
      :phases {:configure (plan-fn
                            (let [f (tmpfile)
                                  u (:username (admin-user))]
                              (spit f s)
                              (directory "some/path")
                              (remote-file "some/path/file"
                                           :local-file (.getPath f)
                                           :owner u)
                              (directory "/var/lib")
                              (remote-file "/var/lib/file"
                                           :local-file (.getPath f))
                              (delete-local-path (.getPath f))))
               :test (plan-fn
                       (let [u (:username (admin-user))]
                         (exec-script*
                          (testing-script
                           "remote-file"
                           (is-true
                            (file-exists? "some/path/file")
                            "local-file copied correctly")
                           (is=
                            ~u
                            @("stat" "-c" "%U" "some/path/file")
                            "local-file has correct ownership")
                           (is-true
                            (file-exists?
                             (str "/var/lib/pallet/admin-home/" ~u
                                  "/some/path/file"))
                            "local-file copied correctly to parallel tree")
                           (is-true
                            (file-exists?
                             (str "/var/lib/pallet/admin-home/" ~u
                                  "/some/path/file.md5"))
                            "local-file generated md5 in parallel tree")
                           (is-true
                            (file-exists? "/var/lib/file")
                            "local-file copied correctly")))))}
      :roles #{:live-test :remote-file})))

(def rsync-test
  (let [s (rand-str 10)]
    (group-spec "rsync-test"
      :phases {:configure (plan-fn
                            (let [d (tmpdir)
                                  f (file d "afile")
                                  u (:username (admin-user))]
                              (spit f s)
                              (directory "some/path" :owner "duncan")
                              (rsync-directory
                               (str (.getPath d) "/")
                               "some/path/")
                              (delete-local-path (.getPath f))))
               :test (plan-fn
                       (let [u (:username (admin-user))]
                         (exec-script*
                          (testing-script
                           "remote-file"
                           (is-true
                            (file-exists? "some/path/afile")
                            "rsync copied correctly")
                           ))))}
      :roles #{:live-test :rsync})))


;;; This is to test p.c.operations.  It runs a sequential lift over two nodes
;;; inside the configure phase.
(declare ops-test-plan)
(def operations-test
  (group-spec "ops-test"
    :count 2
    :phases {:settings (plan-fn
                         (let [nv (exec-script ("hostname"))]
                           (assoc-in-settings [:ops :hostname] nv)))
             :configure (plan-fn
                          (on-one-node  ; run on just one of the two nodes
                           [:ops]
                           (as-action
                            ;; use a new thread so session is not bound
                            @(future
                               (ops-test-plan
                                (compute-service)
                                (distinct
                                 (map #(dissoc % :node) (targets))))))))
             :tag (plan-fn
                    (let [{:keys [hostname]} (get-settings :ops)]
                      (tag! (target-node) "hostname" (:out hostname))))
             :test (plan-fn
                     ;; assert that every node has an opstest tag
                     (as-action
                      (assert
                       (every?
                        #(not (blank? (tag % "hostname")))
                        (target-nodes))))
                     ;; reset the tags
                     (as-action (tag! (target-node) "hostname" "")))}
    :roles #{:live-test :ops}))

(defn ops-test-plan
  [compute groups]
  ;; Run settings on all the nodes to get settings
  (let [nodes @(operate (ops/group-nodes compute groups))
        {:keys [plan-state]} @(operate (ops/lift nodes [:settings] {} {} {}))]
    (assert (= 2 (count nodes)) "Incorrect node count")
    (assert (every? #(ps/get-settings plan-state (id (:node %)) :ops {}) nodes)
            "Has hostname in :ops settings for each node")
    ;; set the tag on the first node
    @(operate (ops/lift [(first nodes)] [:tag] {} plan-state {}))
    (assert (not (blank? (tag (:node (first nodes)) "hostname")))
            "first node has tag")
    (assert (blank? (tag (:node (second nodes)) "hostname"))
            "second node has no tag")
    ;; set the tag on the second node
    @(operate (ops/lift [(second nodes)] [:tag] {} plan-state {}))
    (assert (not (blank? (tag (:node (first nodes)) "hostname")))
            "first node has tag")
    (assert (not (blank? (tag (:node (second nodes)) "hostname")))
            "second node has tag")))

;;; Test rolling-list
(declare rolling-test-plan)
(def rolling-lift-test
  (group-spec "rolling-test"
    :count 2
    :phases {:settings (plan-fn
                         (let [nv (exec-script ("hostname"))]
                           (assoc-in-settings [:rolling :hostname] nv)))
             :configure (plan-fn
                          (infof "rolling-test-plan configure")
                          (on-one-node  ; run on just one of the two nodes
                           [:rolling]
                           (as-action
                            (infof "rolling-test-plan configure action")
                            ;; use a new thread so session is not bound
                            @(future
                               (rolling-test-plan
                                (compute-service)
                                (distinct
                                 (map #(dissoc % :node) (targets))))))))
             :tag (plan-fn
                       (let [{:keys [hostname]} (get-settings :rolling)]
                         (infof "rolling-lift-test tag with %s" (:out hostname))
                         (tag! (target-node) "hostname" (:out hostname))))
             :test (plan-fn
                     ;; assert that every node has a hostname tag
                     (as-action
                      (assert
                       (every?
                        #(not (blank? (tag % "hostname")))
                        (target-nodes))))
                     ;; reset the tags
                     (as-action (tag! (target-node) "hostname" "")))}
    :roles #{:live-test :rolling}))

(defn rolling-test-plan
  [compute groups]
  ;; Run settings on all the nodes to get settings
  (infof "rolling-test-plan applying :settings")
  (let [nodes (api/group-nodes compute groups)
        {:keys [plan-state]} (api/lift-nodes nodes [:settings] {} {})]
    (infof "rolling-test-plan applying rolling lift")
    (infof "rolling-test-plan plan-state %s" plan-state)
    (assert (= 2 (count nodes)) "Incorrect node count")
    (assert
     (every? #(ps/get-settings plan-state (id (:node %)) :rolling {}) nodes)
     "Has hostname in :rolling settings for each node")
    (api/lift-nodes nodes [:tag]
                    :plan-state plan-state
                    :partition-by identity
                    :post-phase-fsm (ops/delay 10 :s))))

;;; test partitioning via metadata
(def partitioning-test
  (group-spec "rolling-test"
    :count 2
    :phases {:settings (plan-fn
                         (assoc-in-settings [:rolling :hostname] (target-name)))
             :tag (with-meta
                    (plan-fn
                      (let [{:keys [hostname]} (get-settings :rolling)]
                        (debugf "rolling-lift-test tag with %s" hostname)
                        (tag! (target-node) "hostname" hostname)))
                    {:partition-f #(partition 1 %)
                     :post-phase-f (fn [_ _ _] (Thread/sleep 10000))})
             :ls (with-meta
                   (plan-fn
                     (exec-script "ls"))
                   {:phase-execution-f (execute-on-unflagged :ls)})
             :test (plan-fn
                     ;; assert that every node has a hostname tag
                     (as-action
                      (assert
                       (every?
                        #(not (blank? (tag % "hostname")))
                        (target-nodes))))
                     ;; reset the tags
                     (as-action (tag! (target-node) "hostname" "")))}
    :roles #{:live-test :partition}))
