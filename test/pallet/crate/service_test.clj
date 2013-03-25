(ns pallet.crate.service-test
  "Provide a test suite for service supervision implementations"
  (:require
   [clojure.test :refer [deftest is testing]]
   [pallet.actions :refer [exec-checked-script exec-script return-value-expr]]
   [pallet.crate.service :refer :all]))

(defn service-supervisor-test
  [supervisor
   {:keys [service-name] :as config}
   {:keys [process-name] :as supervisor-options}]
  (let [process-name (or process-name service-name)]
    (service-supervisor
     supervisor config
     (assoc supervisor-options :action :enable))
    (testing "can start"
      (service-supervisor
       supervisor config
       (assoc supervisor-options :action :start :if-stopped true))
      (exec-checked-script
       "check process is up"
       ("pgrep" -f (quoted ~(name process-name)))))
    (testing "can restart"
      (let [pid (exec-script ("pgrep" -f (quoted ~(name process-name))))]
        (service-supervisor
         supervisor config
         (assoc supervisor-options :action :restart))
        (let [pid2 (exec-checked-script
                    "check process is up after restart"
                    ("pgrep" -f (quoted ~(name process-name))))]
          (return-value-expr [pid pid2]
                             (assert (not= (:out pid) (:out pid2))
                                     (str "old pid: " (:out pid)
                                          " new pid: " (:out pid2)))))))
    (testing "can stop"
      (service-supervisor
       supervisor config
       (assoc supervisor-options :action :stop))
      (exec-checked-script
       "check process is down"
       (not ("pgrep" -f (quoted ~(name process-name))))))))
