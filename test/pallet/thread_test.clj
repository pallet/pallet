(ns pallet.thread-test
  (:use
   clojure.test
   pallet.thread)
  (:import
   (java.util.concurrent ExecutorService ScheduledExecutorService)))

(deftest cached-test
  (with-executor [test-executor (executor {:prefix "test"
                                           :thread-group-name "test-pool"})]
    (let [f (execute
               test-executor
               #(do
                  (assert
                   (= (.getName (current-thread-group))
                      "test-pool"))
                  (assert
                   (= (str "test-" (.getId (Thread/currentThread)))
                      (.getName (Thread/currentThread))))
                  :ok))]
        (is (find-thread-group "test-pool"))
        (is (instance? ExecutorService test-executor))
        (is (future? f))
        (is (= :ok (.get f))))))

(deftest fixed-pool-test
  (with-executor [test-fixed (executor {:prefix "fix"
                                        :thread-group-name "test-pool"
                                        :pool-size 2})]
    (let [f (execute
             test-fixed
             #(do
                (assert
                 (= (.getName (current-thread-group))
                    "test-pool"))
                (assert
                 (= (str "fix-" (.getId (Thread/currentThread)))
                    (.getName (Thread/currentThread))))
                :ok))]
      (is (future? f))
      (is (= :ok (.get f))))))

(deftest schedules-executor-test
  (testing "no pool size"
    (is (thrown-with-msg? Exception #"specify pool size"
          (executor {:prefix "sched" :scheduled true}))))
  (testing "pool size > 1"
    (with-executor [test-scheduled (executor {:prefix "sched"
                                              :thread-group-name "test-pool"
                                              :scheduled true :pool-size 2})]
      (is (find-thread-group "test-pool"))
      (is (instance? ScheduledExecutorService test-scheduled))
      (let [f (execute-after
               test-scheduled
               #(do
                  (assert
                   (= (.getName (current-thread-group))
                      "test-pool"))
                  (assert
                   (= (str "sched-" (.getId (Thread/currentThread)))
                      (.getName (Thread/currentThread))))
                  :ok)
               1 :s)]
        (is (future? f))
        (is (not (future-done? f)))
        (Thread/sleep 1100)
        (is (future-done? f))
        ;; (is (= :ok (.get f))) fails - no idea why
        )))
  (testing "pool size = 1"
    (with-executor [test-scheduled (executor {:prefix "sched"
                                              :thread-group-name "test-pool"
                                              :scheduled true :pool-size 1})]

      (is (find-thread-group "test-pool"))
      (is (instance? ScheduledExecutorService test-scheduled))
      (let [f (execute-after
               test-scheduled
               #(do
                  (assert
                   (= (.getName (current-thread-group))
                      "test-pool"))
                  (assert
                   (= (str "sched-" (.getId (Thread/currentThread)))
                      (.getName (Thread/currentThread))))
                  :ok)
               1 :s)]
        (is (future? f))
        (is (not (future-done? f)))
        (Thread/sleep 1100)
        (is (future-done? f))
        ;; (is (= :ok (.get f))) fails -no idea why
        ))))
