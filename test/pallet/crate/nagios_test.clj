(ns pallet.crate.nagios-test
  (:use pallet.crate.nagios)
  (:require
   [pallet.crate.nagios-config :as nagios-config]
   [pallet.compute :as compute]
   [pallet.stevedore :as stevedore]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.file :as file]
   [clojure.string :as string])
  (:use clojure.test
        pallet.test-utils))

(use-fixtures :each reset-default-parameters)

(deftest host-service-test
  (testing "config"
    (is (= (str
            (remote-file/remote-file*
             "/etc/nagios3/conf.d/pallet-host-*.cfg" :action :delete :force true)
            (remote-file/remote-file*
             "/etc/nagios3/conf.d/pallet-host-tag.cfg"
             :content
             (str
              "\ndefine host {\n use generic-host\n host_name tag\n alias tag\n address null\n}\n"
              (define-service {:check_command "check_cmd"
                               :service_description "Service Name"
                               :host_name "tag"
                               :notification_interval 0
                               :use "generic-service"}))
             :owner "root"))
           (string/join
            [(test-resource-build
             [(compute/make-node "tag") {:image [:ubuntu]}]
             (nagios-config/service
              {:host_name "h"
               :check_command "check_cmd"
               :service_description "Service Name"}))
            (test-resource-build
             [(compute/make-node "tag") {:image [:ubuntu]}]
             (hosts))])))))

(deftest define-contact-test
  (is (= "define contact{\n email email\n contact_name name\n}\n"
         (define-contact {:contact_name "name" :email "email"}))))

(deftest contact-test
  (is (= (stevedore/do-script
          (remote-file/remote-file*
           "/etc/nagios3/conf.d/pallet-contacts.cfg"
           :action :delete :force true)
          (remote-file/remote-file*
           "/etc/nagios3/conf.d/pallet-contacts.cfg"
           :owner "root"
           :content (str
                     (define-contact {:contact_name "name"
                                      :email "email"
                                      :contactgroups ["admin" "ops"]})
                     \newline
                     (define-contact {:contact_name "name2"
                                      :email "email2"
                                      :contactgroups ["admin"]})
                     \newline
                     (define-contactgroup {:contactgroup_name "admin"})
                     \newline
                     (define-contactgroup {:contactgroup_name "ops"}))))
         (test-resource-build
          [nil {}]
          (contact {:contact_name "name"
                    :email "email"
                    :contactgroups ["admin" "ops"]})
          (contact {:contact_name "name2" :email "email2"
                    :contactgroups [:admin]})))))
