(ns pallet.crate.hudson
 "Installation of hudson"
  (:use
   [pallet.utils :only [cmd-join]]
   [pallet.resource.service :only [service]]
   [pallet.resource.directory :only [directory directory*]]
   [pallet.resource.remote-file :only [remote-file remote-file*]]
   [pallet.resource.user :only [user]]
   [clojure.contrib.prxml :only [prxml]]
   [clojure.contrib.logging]
   [clojure.contrib.def])
  (:require
   [pallet.crate.tomcat :as tomcat]
   [net.cgrand.enlive-html :as xml]
   [pallet.enlive :as enlive]
   [pallet.target :as target]
   [pallet.resource :as resource]))

(def hudson-data-path "/var/lib/hudson")
(def hudson-owner "root")
(def hudson-user (atom "hudson"))
(def hudson-group (atom "hudson"))

(defn hudson-user-name [] @hudson-user)
(defn hudson-group-name [] @hudson-group)

(defvar- *maven-file* "hudson.tasks.Maven.xml")
(defvar- *maven2-job-config-file* "job/maven2_config.xml")
(defvar- *git-file* "scm/git.xml")

(defn path-for
  "Get the actual filename corresponding to a template."
  [base] (str "crate/hudson/" base))


(defn tomcat-deploy
  "Install hudson on tomcat"
  []
  (trace (str "Hudson - install on tomcat"))
  (reset! hudson-user (tomcat/tomcat-user-name))
  (reset! hudson-group (tomcat/tomcat-group-name))

  (let [file (str hudson-data-path "/hudson.war")]
    (directory
     hudson-data-path
     :owner hudson-owner :group (hudson-group-name) :mode "775")
    (remote-file file
     :url "http://hudson-ci.org/latest/hudson.war"
     :md5  "680e1525fca0562cfd19552b8d8174e2")
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
    (tomcat/deploy file "hudson")))

(defn tomcat-undeploy
  "Remove hudson on tomcat"
  []
  (trace (str "Hudson - uninistall from tomcat"))
  (reset! hudson-user (tomcat/tomcat-user-name))
  (reset! hudson-group (tomcat/tomcat-group-name))

  (tomcat/undeploy "hudson")
  (let [file (str hudson-data-path "/hudson.war")]
    (tomcat/policy 99 "hudson" nil :action :remove)
    (tomcat/application-conf "hudson" nil :action :remove))
  (directory hudson-data-path :action :delete :force true :recursive true))

(def hudson-plugins
     {:git {:url "https://hudson.dev.java.net/files/documents/2402/135478/git.hpi"
            :md5 "98db63b28bdf9ab0e475c2ec5ba209f1"}})

(defn plugin*
  [plugin & options]
  (info (str "Hudson - add plugin " plugin))
  (let [opts (apply hash-map options)
        src (merge (get hudson-plugins plugin {})
                   (select-keys opts [:url :md5]))]
    (cmd-join
     [(directory* (str hudson-data-path "/plugins"))
      (apply remote-file*
       (str hudson-data-path "/plugins/" (name plugin) ".hpi")
       (apply concat src))])))

(resource/defresource plugin
  "Install a hudson plugin.  The plugin should be a keyword.
  :url can be used to specify a string containing the download url"
  plugin* [plugin & options])

;; (defn hudson-config*
;;   [& options])

;; (def hudson-config-args (atom []))

;; (defn apply-hudson-config [args]
;;   (apply-templates
;;    sudoer-templates
;;    (reduce merge [(array-map) (array-map) (default-specs)] args)))

;; (defresource hudson-config
;;   "Configure hudson"
;;   hudson-config-args apply-hudson-config [& options])

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
    (enlive/transform-if-let [maven-name (options :maven-name)]
                             (xml/content maven-name))
    [:mavenOpts]
    (enlive/transform-if-let [maven-opts (options :maven-opts)]
                             (xml/content maven-opts))
    [:goals]
    (enlive/transform-if-let [goals (options :goals)]
                             (xml/content goals))
    [:groupId]
    (enlive/transform-if-let [group-id (options :group-id)]
                             (xml/content group-id))
    [:artifactId]
    (enlive/transform-if-let [artifact-id (options :artifact-id)]
                             (xml/content artifact-id)))
   scm-type scms options))

(defmulti output-build-for
  "Output the build definition for specified type"
  (fn [build-type node-type scm-type scms options] build-type))

(defmethod output-build-for :maven2
  [build-type node-type scm-type scms options]
  (let [scm-type (or scm-type (some determine-scm-type scms))]
    (maven2-job-xml node-type scm-type scms options)))


(defn job*
  [build-type name & options]
  (trace (str "Hudson - configure job " name))
  (let [opts (apply hash-map options)]
    (cmd-join
     [(directory* (str hudson-data-path "/jobs/" name) :p true)
      (remote-file*
       (str hudson-data-path "/jobs/" name "/config.xml" )
       :content
       (output-build-for
        build-type
        (target/node-type)
        (opts :scm-type)
        (normalise-scms (opts :scm))
        (dissoc opts :scm :scm-type)))
      (directory*
       hudson-data-path
       :owner hudson-owner :group (hudson-group-name)
       :mode "g+w"
       :recursive true)])))

(resource/defresource job
  "Configure a hudson job.
build-type - :maven2
name - name to be used in links
options are:
:scm-type  determine scm type, eg. :git
:scm a sequence of scm repositories, each a string or a sequence.
     If a sequence, options are
        :name, :refspec, :receivepack, :uploadpack and :tagopt
:description \"a descriptive string\"
:branches [\"branch1\" \"branch2\"]
"
  job* [build-type name & options])



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

(defn apply-hudson-maven [args]
  (cmd-join
   [(directory* "/usr/share/tomcat6/.m2" :group (hudson-group-name) :mode "g+w")
    (remote-file*
     (str hudson-data-path "/" *maven-file*)
     :content (apply
               str (hudson-maven-xml
                    (target/node-type) args))
     :owner hudson-owner
     :group (hudson-group-name))]))


(resource/defaggregate maven
  "Configure a maven instance for hudson."
  apply-hudson-maven [name version])
