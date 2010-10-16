(ns pallet.crate.nagios-test
  (:use pallet.crate.nagios)
  (:require
   [pallet.crate.nagios-config :as nagios-config]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.file :as file]
   [clojure.string :as string]
   [pallet.test-utils :as test-utils])
  (:use clojure.test))

(use-fixtures :once test-utils/with-ubuntu-script-template)

(deftest host-service-test
  (testing "config"
    (is (= (str
            (remote-file/remote-file*
             {}
             "/etc/nagios3/conf.d/pallet-host-*.cfg" :action :delete :force true)
            (remote-file/remote-file*
             {}
             "/etc/nagios3/conf.d/pallet-host-host-tag-id.cfg"
             :content
             (str
              "\ndefine host "
              "{\n use generic-host\n host_name host-tag-id\n alias host-tag-id\n address 1.2.3.4\n}\n"
              (define-service {:check_command "check_cmd"
                               :service_description "Service Name"
                               :host_name "host-tag-id"
                               :notification_interval 0
                               :use "generic-service"}))
             :owner "root"))
           (let [node (test-utils/make-node
                       "tag" :id "id" :public-ips ["1.2.3.4"])]
             (first
              (test-utils/build-resources
               [:target-node node
                :all-nodes [node]
                :target-nodes [node]
                :node-type {:image {:os-family :ubuntu}}]
               (nagios-config/service
                {:check_command "check_cmd"
                 :service_description "Service Name"})
               (hosts)))))))
  (testing "unmanaged host config"
    (is (= (str
            (remote-file/remote-file*
             {}
             "/etc/nagios3/conf.d/pallet-host-*.cfg"
             :action :delete :force true)
            (remote-file/remote-file*
             {}
             "/etc/nagios3/conf.d/pallet-host-tag.cfg"
             :content
             (str
              "\ndefine host {\n use generic-host\n host_name tag\n alias tag\n address 1.2.3.4\n}\n"
              (define-service {:check_command "check_cmd"
                               :service_description "Service Name"
                               :host_name "tag"
                               :notification_interval 0
                               :use "generic-service"}))
             :owner "root"))
           (str
            (let [node (test-utils/make-node
                        "tag" :id "id" :public-ips ["1.2.3.4"])]
              (first
               (test-utils/build-resources
                [:target-node node
                 :all-nodes [node]
                 :target-nodes [node]
                 :node-type {:image {:os-family :ubuntu}}]
                (unmanaged-host "1.2.3.4" "tag")
                (nagios-config/service
                 {:host_name "tag"
                  :check_command "check_cmd"
                  :service_description "Service Name"})
                (hosts)))))))))

(deftest define-contact-test
  (is (= "define contact{\n email email\n contact_name name\n}\n"
         (define-contact {:contact_name "name" :email "email"}))))

(deftest contact-test
  (is (= (stevedore/do-script
          (remote-file/remote-file*
           {}
           "/etc/nagios3/conf.d/pallet-contacts.cfg"
           :action :delete :force true)
          (remote-file/remote-file*
           {}
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
         (first
          (test-utils/build-resources
           []
           (contact {:contact_name "name"
                     :email "email"
                     :contactgroups ["admin" "ops"]})
           (contact {:contact_name "name2" :email "email2"
                     :contactgroups [:admin]}))))))
