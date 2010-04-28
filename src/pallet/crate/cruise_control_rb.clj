(ns pallet.crate.cruise-control-rb
 "Installation cruise-control.rb"
 (:require
  [pallet.stevedore :as stevedore]
  [pallet.resource :as resource]
  [pallet.template :as template]
  [pallet.utils :as utils]
  [pallet.target :as target]
  [pallet.resource.file :as file]
  [pallet.resource.remote-file :as remote-file]
  [pallet.resource.service :as service]
  [pallet.resource.user :as user]
  [pallet.resource.directory :as directory]
  [pallet.resource.exec-script :as exec-script]))

(def cruise-control-rb-downloads
     {"1.4.0" [ "http://rubyforge.org/frs/download.php/59598/cruisecontrol-1.4.0.tgz"
                "772d8300cc61a581f55e9f1e33eacb6b"]})

(def cruise-control-rb-install "/opt/cruisecontrolrb")
(def cruise-control-rb-data "/var/lib/cruisecontrolrb")
(def cruise-control-rb-user "ccrb")
(def cruise-control-rb-owenr cruise-control-rb-user)
(def cruise-control-rb-group "root")
(def cruise-control-rb-init-script "crate/cruise-control-rb/cruisecontrol.rb")

(defn determine-scm-type
  "determine the scm type"
  [scm-path]
  (cond
   (.contains scm-path "git") :git
   (.contains scm-path "svn") :svn
   (or (.contains scm-path "cvs")
       (.contains scm-path "pserver")) :cvs
   (.contains scm-path "bk") :bitkeeper
   :else nil))

(defn cruise-control-rb
  "Downloads and installs the specified version of cruisecontrol.rb.  Creates a
cruiscontrol.rb init script."
  ([] (cruise-control-rb "1.4.0"))
  ([version]
     (let [info (cruise-control-rb-downloads version)
           basename (str "cruisecontrol-" version)
           tarfile (str basename ".tgz")
           tarpath (str (stevedore/script (tmp-dir)) "/" tarfile)]
       (remote-file/remote-file tarpath :url (first info) :md5 (second info))
       (user/user cruise-control-rb-user :home cruise-control-rb-data :shell :false)
       (directory/directory cruise-control-rb-install)
       (directory/directory cruise-control-rb-data :owner cruise-control-rb-user)
       (exec-script/exec-script
        (stevedore/script
         (cd ~cruise-control-rb-install)
         (tar xz --strip-components=1 -f ~tarpath)))
       (file/file
        (str cruise-control-rb-install "/public/stylesheets/site.css")
        :owner cruise-control-rb-user)
       (file/file
        (str cruise-control-rb-install "/log/production.log")
        :owner cruise-control-rb-user
        :mod "0664")
       (file/file
        (str cruise-control-rb-install "/log/add_project.log")
        :owner cruise-control-rb-user
        :mod "0664")
       (directory/directory
        (str cruise-control-rb-install "/tmp")
        :owner cruise-control-rb-user)
       (directory/directory
        (str cruise-control-rb-install "/tmp")
        :owner cruise-control-rb-user))))

(defn cruise-control-rb-init*
  [& options]
  (service/init-script*
     "cruisecontrol.rb"
     :content (utils/load-resource-url
               (template/find-template
                cruise-control-rb-init-script
                (target/node-type)))
     :literal true))

(resource/defresource cruise-control-rb-init
  "Creates a cruiscontrol.rb init script."
  cruise-control-rb-init* [])

(defn- project-path [project]
  (str cruise-control-rb-data "/projects/" project))

(defn cruise-control-rb-job*
  [name repository-url & options]
  (let [options (merge {:action :create} (apply hash-map options))]
    (condp = (options :action)
      :create (stevedore/checked-script "cruise-control-rb"
               (export ~(format "CRUISE_DATA_ROOT=%s" cruise-control-rb-data))
               (if-not (file-exists? ~(project-path name))
                 (sudo
                  -u ~cruise-control-rb-user
                  ~(format "CRUISE_DATA_ROOT=%s" cruise-control-rb-data)
                  ~(str cruise-control-rb-install "/cruise")
                  add ~name
                  --repository ~repository-url
                  --source-control ~(determine-scm-type repository-url)
                  ~(stevedore/option-args
                    (apply concat (dissoc options :action))))))
      :remove (directory/directory*
               (project-path name)
               :action :remove :recursive true :force true))))

(resource/defresource cruise-control-rb-job
  "Add a cruise control.rb job.
Options include:
  :branch name   -- specify the branch"
  cruise-control-rb-job* [name repository-url & options])
