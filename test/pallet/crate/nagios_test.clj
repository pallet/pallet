(ns pallet.crate.nagios-test
  (:use pallet.crate.nagios)
  (:require
   [pallet.crate.nagios-config :as nagios-config]
   [pallet.compute :as compute]
   [pallet.stevedore :as stevedore]
   [pallet.resource.remote-file :as remote-file]
   pallet.resource.file)
  (:use clojure.test
        pallet.test-utils))

(use-fixtures :each reset-default-parameters)

(deftest host-service-test
  (testing "config"
    (is (= (str
            (stevedore/checked-script
             "delete remote-file /etc/nagios3/conf.d/pallet-host-*.cfg"
             (rm "/etc/nagios3/conf.d/pallet-host-*.cfg" {:force true}))
            (remote-file/remote-file*
             "/etc/nagios3/conf.d/pallet-host-tag.cfg"
             :content "\ndefine host {\n use generic-host\n host_name tag\n alias tag\n address null\n}\n\ndefine service {\n use null\n host_name tag\n service_description Service Name\n check_command check_cmd\n notification_interval null\n}\n"
             :owner "root"))
           (test-resource-build
            [(compute/make-node "tag") {:image [:ubuntu]}]
            (nagios-config/service
             {:command "check_cmd" :service-description "Service Name"})
            (hosts))))))
