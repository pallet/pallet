(ns pallet.crate.hudson
 "Installation of hudson"
  (:use
   [pallet.resource.service :only [service]]
   [pallet.resource.directory :only [directory directory*]]
   [pallet.resource.remote-file :only [remote-file remote-file*]]
   [clojure.contrib.prxml :only [prxml]]
   [clojure.contrib.logging]
   [clojure.contrib.def]
   pallet.thread-expr)
  (:require
   [net.cgrand.enlive-html :as xml]
   [pallet.crate.maven :as maven]
   [pallet.crate.tomcat :as tomcat]
   [pallet.enlive :as enlive]
   [pallet.parameter :as parameter]
   [pallet.resource :as resource]
   [pallet.resource.user :as user]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.string :as string]))

(def hudson-data-path "/var/lib/hudson")
(def hudson-owner "root")
(def hudson-user  "hudson")
(def hudson-group  "hudson")

(defvar- *maven-file* "hudson.tasks.Maven.xml")
(defvar- *maven2-job-config-file* "job/maven2_config.xml")
(defvar- *git-file* "scm/git.xml")

(defn path-for
  "Get the actual filename corresponding to a template."
  [base] (str "crate/hudson/" base))

(defn hudson-url
  [version]
  (if (= version :latest)
    "http://hudson-ci.org/latest/hudson.war"
    (str "http://hudson-ci.org/download/war/" version "/hudson.war")))

(def hudson-md5
     {:latest "5d616c367d7a7888100ae6e98a5f2bd7"
      "1.355" "5d616c367d7a7888100ae6e98a5f2bd7"})

(defn tomcat-deploy
  "Install hudson on tomcat.
     :version version-string   - specify version, eg 1.355, or :latest"
  [request & options]
  (trace (str "Hudson - install on tomcat"))
  (let [options (apply hash-map options)
        version (get options :version :latest)
        user (parameter/get-for-target request [:tomcat :owner])
        group (parameter/get-for-target request [:tomcat :group])
        file (str hudson-data-path "/hudson.war")]
    (->
     request
     (parameter/assoc-for-target
      [:hudson :data-path] hudson-data-path
      [:hudson :owner] hudson-owner
      [:hudson :user] user
      [:hudson :group] group)
     (directory
      hudson-data-path :owner hudson-owner :group group :mode "775")
     (remote-file file :url (hudson-url version) :md5 (hudson-md5 version))
     (tomcat/policy
      99 "hudson"
      {(str "file:${catalina.base}/webapps/hudson/-")
       ["permission java.security.AllPermission"]
       (str "file:" hudson-data-path "/-")
       ["permission java.security.AllPermission"]})
     (tomcat/application-conf
      "hudson"
      (format "<?xml version=\"1.0\" encoding=\"utf-8\"?>
 <Context
 privileged=\"true\"
 path=\"/hudson\"
 allowLinking=\"true\"
 swallowOutput=\"true\"
 >
 <Environment
 name=\"HUDSON_HOME\"
 value=\"%s\"
 type=\"java.lang.String\"
 override=\"false\"/>
 </Context>"
              hudson-data-path))
     (tomcat/deploy "hudson" :remote-file file))))

(defn tomcat-undeploy
  "Remove hudson on tomcat"
  [request]
  (trace (str "Hudson - uninistall from tomcat"))
  (let [hudson-data-path (parameter/get-for-target
                           request [:hudson :data-path])
        file (str hudson-data-path "/hudson.war")]
    (->
     request
     (parameter/assoc-for-target [:hudson] nil)
     (tomcat/undeploy "hudson")
     (tomcat/policy 99 "hudson" nil :action :remove)
     (tomcat/application-conf "hudson" nil :action :remove)
     (directory hudson-data-path :action :delete :force true :recursive true))))

(defmulti plugin-config
  "Plugin configuration"
  (fn [request plugin] plugin))

(defmethod plugin-config :git
  [request plugin]
  (user/user
   request
   (parameter/get-for-target request [:hudson :user])
   :action :manage :comment "hudson"))


(def hudson-plugins
     {:git {:url "https://hudson.dev.java.net/files/documents/2402/135478/git.hpi"
            :md5 "98db63b28bdf9ab0e475c2ec5ba209f1"}})


(defn plugin
  "Install a hudson plugin.  The plugin should be a keyword.
  :url can be used to specify a string containing the download url"
  [request plugin & {:keys [url md5] :as options}]
  (info (str "Hudson - add plugin " plugin))
  (let [src (merge (get hudson-plugins plugin {})
                   (select-keys options [:url :md5]))
        hudson-data-path (parameter/get-for-target
                          request [:hudson :data-path])]
    (-> request
     (directory (str hudson-data-path "/plugins"))
     (apply->
      remote-file
      (str hudson-data-path "/plugins/" (name plugin) ".hpi")
      (apply concat src))
     (plugin-config plugin))))

(defn determine-scm-type
  "determine the scm type"
  [scm-spec]
  (let [scm-path (first scm-spec)]
    (cond
     (.contains scm-path "git") :git
     (.contains scm-path "svn") :svn
     (or (.contains scm-path "cvs")
         (.contains scm-path "pserver")) :cvs
     (.contains scm-path "bk") :bitkeeper
     :else nil)))

(defmulti output-scm-for
  "Output the scm definition for specified type"
  (fn [scm-type node-type scm-path options] scm-type))


;; "Generate git scm configuration for job content"
(enlive/defsnippet git-job-xml
  (path-for *git-file*) node-type
  [node-type scm-path options]
  [:#url]
  (xml/do->
   (xml/content scm-path)
   (xml/remove-attr :id))
  [:#refspec]
  (xml/do->
   (xml/remove-attr :id)
   (enlive/transform-if-let [refspec (options :refspec)]
                            (xml/content refspec)))
  [:#receivepack]
  (xml/do->
   (xml/remove-attr :id)
   (enlive/transform-if-let [receivepack (options :receivepack)]
                            (xml/content receivepack)))
  [:#uploadpack]
  (xml/do->
   (xml/remove-attr :id)
   (enlive/transform-if-let [upload-pack (options :uploadpack)]
                            (xml/content upload-pack)))
  [:#tagopt]
  (xml/do->
   (xml/remove-attr :id)
   (enlive/transform-if-let [tagopt (options :tagopt)]
                            (xml/content tagopt))))

(defmethod output-scm-for :git
  [scm-type node-type scm-path options]
  (git-job-xml node-type scm-path options))


(defn normalise-scms [scms]
  (map #(if (string? %) [%] %) scms))

(def class-for-scm
     { :git "hudson.plugins.git.GitSCM"})

(enlive/deffragment branch-transform
  [branch]
  [:name]
  (xml/content branch))

(defn maven2-job-xml
  "Generate maven2 job/config.xml content"
  [node-type scm-type scms options]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *maven2-job-config-file*) node-type [scm-type scms options]
    [:scm] (xml/set-attr :class (class-for-scm scm-type))
    [:remoteRepositories :> :*] nil
    [:remoteRepositories]
    (apply
     xml/prepend
     (mapcat #(output-scm-for
               scm-type
               node-type
               (first %)
               (if (seq (next %)) (apply hash-map %) {}))
             scms))
    [:hudson.plugins.git.BranchSpec]
    (xml/clone-for [branch (get options :branches ["origin/master"])]
                   (branch-transform branch))
    [:mavenName]
    (enlive/transform-if-let [maven-name (:maven-name options)]
                             (xml/content maven-name))
    [:mavenOpts]
    (enlive/transform-if-let [maven-opts (:maven-opts options)]
                             (xml/content maven-opts))
    [:goals]
    (enlive/transform-if-let [goals (:goals options)]
                             (xml/content goals))
    [:groupId]
    (enlive/transform-if-let [group-id (:group-id options)]
                             (xml/content group-id))
    [:artifactId]
    (enlive/transform-if-let [artifact-id (:artifact-id options)]
                             (xml/content artifact-id)))
   scm-type scms options))

(defmulti output-build-for
  "Output the build definition for specified type"
  (fn [build-type node-type scm-type scms options] build-type))

(defmethod output-build-for :maven2
  [build-type node-type scm-type scms options]
  (let [scm-type (or scm-type (some determine-scm-type scms))]
    (maven2-job-xml node-type scm-type scms options)))


(defn job
  "Configure a hudson job.
build-type - :maven2
name - name to be used in links
options are:
:scm-type  determine scm type, eg. :git
:scm a sequence of scm repositories, each a string or a sequence.
     If a sequence, options are
        :name, :refspec, :receivepack, :uploadpack and :tagopt
:description \"a descriptive string\"
:branches [\"branch1\" \"branch2\"]"
  [request build-type job-name & {:keys [refspec receivepack uploadpack
                                         tagopt description branches scm
                                         scm-type]
                                  :as options}]
  (let [hudson-owner (parameter/get-for-target request [:hudson :owner])
        hudson-group (parameter/get-for-target request [:hudson :group])
        hudson-data-path (parameter/get-for-target
                          request [:hudson :data-path])]
    (trace (str "Hudson - configure job " job-name))
    (->
     request
     (directory (str hudson-data-path "/jobs/" job-name) :p true)
     (remote-file
      (str hudson-data-path "/jobs/" job-name "/config.xml" )
      :content
      (output-build-for
       build-type
       (:node-type request)
       (:scm-type options)
       (normalise-scms (:scm options))
       (dissoc options :scm :scm-type)))
     (directory
      hudson-data-path
      :owner hudson-owner :group hudson-group
      :mode "g+w"
      :recursive true))))



(enlive/deffragment hudson-task-transform
  [name version]
  [:name]
  (xml/content name)
  [:id]
  (xml/content version))

(defn hudson-maven-xml
  "Generate hudson.task.Maven.xml content"
  [node-type maven-tasks]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *maven-file*) node-type
    [tasks]
    [:installations :* xml/first-child]
    (enlive/transform-if (seq tasks)
                         (xml/clone-for [task tasks]
                                        (apply hudson-task-transform task))))
   maven-tasks))



(resource/defcollect maven-config
  "Configure a maven instance for hudson."
  {:use-arglist [request name version]}
  (hudson-maven*
   [request args]
   (let [group (parameter/get-for-target request [:hudson :group])
         hudson-owner (parameter/get-for-target request [:hudson :owner])
         hudson-data-path (parameter/get-for-target
                           request [:hudson :data-path])]
     (stevedore/do-script
      (directory* request "/usr/share/tomcat6/.m2" :group group :mode "g+w")
      (directory*
       request hudson-data-path :owner hudson-owner :group group :mode "775")
      (remote-file*
       request
       (str hudson-data-path "/" *maven-file*)
       :content (apply
                 str (hudson-maven-xml
                      (:node-type request) args))
       :owner hudson-owner :group group)))))

(defn maven
  [request name version]
  (let [group (parameter/get-for-target request [:hudson :group])
        hudson-owner (parameter/get-for-target request [:hudson :owner])
        hudson-data-path (parameter/get-for-target
                          request [:hudson :data-path])]
    (->
     request
     (maven/download
      :maven-home (str
                   hudson-data-path "/tools/" (string/replace name #" " "_"))
      :version version :owner hudson-owner :group group)
     (maven-config name version))))
