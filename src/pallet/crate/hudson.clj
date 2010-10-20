(ns pallet.crate.hudson
 "Installation of hudson"
  (:use
   [pallet.resource.service :only [service]]
   [pallet.resource.directory :only [directory*]]
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
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.directory :as directory]
   [pallet.resource.user :as user]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.string :as string]))

(def hudson-data-path "/var/lib/hudson")
(def hudson-owner "root")
(def hudson-user  "hudson")
(def hudson-group  "hudson")

(defvar- *config-file* "config.xml")
(defvar- *user-config-file* "users/config.xml")
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
     {"1.377" "81b602c754fdd28cc4d57a9b82a7c1f0"
      "1.355" "5d616c367d7a7888100ae6e98a5f2bd7"})

(defn tomcat-deploy
  "Install hudson on tomcat.
     :version version-string   - specify version, eg 1.355, or :latest"
  [request & {:keys [version] :or {version :latest} :as options}]
  (trace (str "Hudson - install on tomcat"))
  (let [user (parameter/get-for-target request [:tomcat :owner])
        group (parameter/get-for-target request [:tomcat :group])
        file (str hudson-data-path "/hudson.war")]
    (->
     request
     (parameter/assoc-for-target
      [:hudson :data-path] hudson-data-path
      [:hudson :owner] hudson-owner
      [:hudson :user] user
      [:hudson :group] group)
     (directory/directory
      hudson-data-path :owner hudson-owner :group group :mode "0775")
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
     (directory/directory
      hudson-data-path :action :delete :force true :recursive true))))

(defn download-cli [request]
  (let [user (parameter/get-for-target request [:hudson :admin-user])
        pwd (parameter/get-for-target request [:hudson :admin-password])]
    (remote-file/remote-file
     request
     "hudson-cli.jar"
     :url (if user
            (format
             "http://%s:%s@localhost:8080/hudson/jnlpJars/hudson-cli.jar"
             user pwd)
            "http://localhost:8080/hudson/jnlpJars/hudson-cli.jar"))))

(defn cli [request command]
  (let [user (parameter/get-for-target request [:hudson :admin-user])
        pwd (parameter/get-for-target request [:hudson :admin-password])]
    (format
     "java -jar ~/hudson-cli.jar -s http://localhost:8080/hudson %s %s"
     command
     (if user (format "--username %s --password %s" user pwd) ""))))

(defn hudson-cli
  "Install a hudson cli."
  [request]
  (download-cli request))

(def hudson-plugin-urls
  {:git "http://hudson-ci.org/latest/git.hpi"})

(defn install-plugin [request url]
  (str (cli request (str "install-plugin " (utils/quoted url)))))

(defn plugin-via-cli
  "Install a hudson plugin.  The plugin should be a keyword.
  :url can be used to specify a string containing the download url"
  [request plugin & {:keys [url] :as options}]
  {:pre [(keyword? plugin)]}
  (info (str "Hudson - add plugin " plugin))
  (let [src (or url (plugin hudson-plugin-urls))]
    (-> request
        (hudson-cli)
        (exec-script/exec-checked-script
         (format "installing %s plugin" plugin)
         ~(install-plugin src)))))


(defn cli-command
  "Execute a maven cli command"
  [request message command]
  (-> request
      (hudson-cli)
      (exec-script/exec-checked-script
       message
       ~(str (cli request command)))))

(defn version
  "Show running version"
  [request]
  (cli-command request "Hudson Version: " "version"))

(defn reload-configuration
  "Show running version"
  [request]
  (cli-command request "Hudson reload-configuration: " "reload-configuration"))

(defn build
  "Build a job"
  [request job]
  (cli-command request (format "build %s: " job) (format "build %s" job)))

(defmulti plugin-config
  "Plugin configuration"
  (fn [request plugin options] plugin))

(defmethod plugin-config :git
  [request plugin _]
  (user/user
   request
   (parameter/get-for-target request [:hudson :user])
   :action :manage :comment "hudson"))

(defn truefalse [value]
  (if value "true" "false"))

(defmethod plugin-config :ircbot
  [request plugin options]
  (let [hudson-data-path (parameter/get-for-target
                          request [:hudson :data-path])
        hudson-owner (parameter/get-for-target
                      request [:hudson :owner])
        hudson-group (parameter/get-for-target
                      request [:hudson :group])]
    (remote-file/remote-file
     request
     (format "%s/hudson.plugins.ircbot.IrcPublisher.xml" hudson-data-path)
     :content (with-out-str
                (prxml
                 [:decl! {:version "1.0"}]
                 [:hudson.plugins.ircbot.IrcPublisher_-DescriptorImpl {}
                  [:enabled {} (truefalse (:enabled options))]
                  [:hostname {} (:hostname options)]
                  [:port {} (:port options 6674)]
                  [:password {} (:password options)]
                  [:nick {} (:nick options)]
                  [:nickServPassword {} (:nick-serv-password options)]
                  [:defaultTargets (if (seq (:default-targets options))
                                     {}
                                     {:class "java.util.Collections$EmptyList"})
                   (map #(prxml [:hudson.plugins.im.GroupChatIMMessageTarget {}
                                 [:name {} (:name %)]
                                 [:password {} (:password %)]])
                        (:default-targets options))]
                  [:commandPrefix {}  (:command-prefix options)]
                  [:hudsonLogin {}  (:hudson-login options)]
                  [:hudsonPassword {}  (:hudson-password options)]
                  [:useNotice {}  (truefalse (:use-notice options))]]))
     :literal true
     :owner hudson-owner :group hudson-group :mode "664")))

(defmethod plugin-config :default [request plugin options]
  request)

(def hudson-plugins
  {:git {:url "http://hudson-ci.org/latest/git.hpi"
         :md5 "423afd697acdb2b7728f80573131c15f"}
   :github {:url "http://hudson-ci.org/latest/github.hpi"}
   :instant-messaging {:url
                       "http://hudson-ci.org/latest/instant-messaging.hpi"}
   :ircbot {:url "http://hudson-ci.org/latest/ircbot.hpi"}
   :greenballs {:url "http://hudson-ci.org/latest/greenballs.hpi"}})

 (defn plugin
   "Install a hudson plugin.  The plugin should be a keyword.
   :url can be used to specify a string containing the download url"
  [request plugin & {:keys [url md5] :as options}]
  {:pre [(keyword? plugin)]}
  (info (str "Hudson - add plugin " plugin))
  (let [src (merge (plugin hudson-plugins)
                   (select-keys options [:url :md5]))
        hudson-data-path (parameter/get-for-target
                          request [:hudson :data-path])
        hudson-group (parameter/get-for-target
                      request [:hudson :group])]
    (-> request
     (directory/directory (str hudson-data-path "/plugins"))
     (apply->
      remote-file
      (str hudson-data-path "/plugins/" (name plugin) ".hpi")
      :group hudson-group :mode "0664"
      (apply concat src))
     (plugin-config plugin options))))


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
  {:git "hudson.plugins.git.GitSCM"})

(def class-for-scm-remote
  {:git "org.spearce.jgit.transport.RemoteConfig"})

(enlive/deffragment branch-transform
  [branch]
  [:name]
  (xml/content branch))

(defmulti publisher-config
  "Publisher configuration"
  (fn [[publisher options]] publisher))

(def imstrategy {:all "ALL"})

(defmethod publisher-config :ircbot
  [[_ options]]
  (with-out-str
    (prxml [:hudson.plugins.ircbot.IrcPublisher {}
            [:targets {}
             [:hudson.plugins.im.GroupChatIMMessageTarget {}
              (map #(prxml
                     [:name {} (:name %)]
                     [:password {} (:password %)])
                   (:targets options))]]
            [:strategy {:class "hudson.plugins.im.NotificationStrategy"}
             (imstrategy (:strategy options :all))]
            [:notifyOnBuildStart {}
             (if (:notify-on-build-start options) "true" false)]
            [:notifySuspects {}
             (if (:notify-suspects options) "true" false)]
            [:notifyCulprits {}
             (if (:notify-culprits options) "true" false)]
            [:notifyFixers {}
             (if (:notify-fixers options) "true" false)]
            [:notifyUpstreamCommitters {}
             (if (:notify-upstream-committers options) "true" false)]
            [:channels {}]])))

;; todo
;; -    <authorOrCommitter>false</authorOrCommitter>
;; -    <clean>false</clean>
;; -    <wipeOutWorkspace>false</wipeOutWorkspace>
;; -    <buildChooser class="hudson.plugins.git.util.DefaultBuildChooser"/>
;; -    <gitTool>Default</gitTool>
;; -    <submoduleCfg class="list"/>
;; -    <relativeTargetDir></relativeTargetDir>
;; -    <excludedRegions></excludedRegions>
;; -    <excludedUsers></excludedUsers>
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

    [:branches :> :*]
    (xml/clone-for [branch (:branches options ["*"])]
                   (branch-transform branch))
    [:mavenName]
    (enlive/transform-if-let [maven-name (:maven-name options)]
                             (xml/content maven-name))
    [:mavenOpts]
    (enlive/transform-if-let [maven-opts (:maven-opts options)]
                             (xml/content
                              maven-opts))
    [:goals]
    (enlive/transform-if-let [goals (:goals options)]
                             (xml/content goals))
    [:groupId]
    (enlive/transform-if-let [group-id (:group-id options)]
                             (xml/content group-id))
    [:artifactId]
    (enlive/transform-if-let [artifact-id (:artifact-id options)]
                             (xml/content artifact-id))
    [:properties :* :projectUrl]
    (enlive/transform-if-let [github-url (-> options :github :projectUrl)]
                             (xml/content github-url))
    [:mergeOptions]
    (let [target (:merge-target options)]
      (if target
        (xml/transformation
         [:mergeTarget] (xml/content target)
         [:mergeRemote] (xml/set-attr
                         :reference (format
                                     "../../remoteRepositories/%s"
                                     (class-for-scm-remote scm-type))))
        (xml/content "")))
    [:authToken] (if-let [token (:auth-token options)]
                   (xml/content token))
    [:publishers]
    (xml/html-content
     (string/join (map publisher-config (:publishers options))))
    [:aggregatorStyleBuild] (xml/content
                             (truefalse
                              (:aggregator-style-build options true))))
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
                                         scm-type merge-target]
                                  :as options}]
  (let [hudson-owner (parameter/get-for-target request [:hudson :owner])
        hudson-group (parameter/get-for-target request [:hudson :group])
        hudson-data-path (parameter/get-for-target
                          request [:hudson :data-path])]
    (trace (str "Hudson - configure job " job-name))
    (->
     request
     (directory/directory (str hudson-data-path "/jobs/" job-name) :p true
                :owner hudson-owner :group hudson-group :mode  "0775")
     (remote-file
      (str hudson-data-path "/jobs/" job-name "/config.xml")
      :content
      (output-build-for
       build-type
       (:node-type request)
       (:scm-type options)
       (normalise-scms (:scm options))
       (dissoc options :scm :scm-type))
      :owner hudson-owner :group hudson-group :mode "0664")
     (directory/directory
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


(defn- hudson-tool-path
  [hudson-data-path name]
  (str hudson-data-path "/tools/" (string/replace name #" " "_")))

(defn hudson-maven-xml
  "Generate hudson.task.Maven.xml content"
  [node-type hudson-data-path maven-tasks]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *maven-file*) node-type
    [tasks]
    [:installations xml/first-child]
    (xml/clone-for [task tasks]
                   [:name] (xml/content (first task))
                   [:home] (xml/content
                            (hudson-tool-path hudson-data-path (first task)))
                   [:properties] nil))
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
                      (:node-type request) hudson-data-path args))
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
      :maven-home (hudson-tool-path hudson-data-path name)
      :version version :owner hudson-owner :group group)
     (maven-config name version))))

(defn hudson-user-xml
  "Generate user config.xml content"
  [node-type user]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *user-config-file*) node-type
    [user]
    [:fullName] (xml/content (:full-name user))
    [(xml/tag= "hudson.security.HudsonPrivateSecurityRealm_-Details")
     :passwordHash]
    (:password-hash user)
    [(xml/tag= "hudson.tasks.Mailer_-UserProperty") :emailAddress]
    (:email user))
   user))

(defn user
  "Add a hudson user, using hudson's user database."
  [request username {:keys [full-name password-hash email] :as user}]
  (let [group (parameter/get-for-target request [:hudson :group])
        hudson-owner (parameter/get-for-target request [:hudson :owner])
        hudson-data-path (parameter/get-for-target
                          request [:hudson :data-path])]
    (-> request
        (directory/directory
         (format "%s/users/%s" hudson-data-path username)
         :owner hudson-owner :group group :mode "0775")
        (remote-file/remote-file
         (format "%s/users/%s/config.xml" hudson-data-path username)
         :content (hudson-user-xml (:node-type request) user)
         :owner hudson-owner :group group :mode "0664"))))

(def security-realm-class
  {:hudson "hudson.security.HudsonPrivateSecurityRealm"})

(def authorization-strategy-class
  {:global-matrix "hudson.security.GlobalMatrixAuthorizationStrategy"})

(def permission-class
  {:computer-configure "hudson.model.Computer.Configure"
   :computer-delete "hudson.model.Computer.Delete"
   :hudson-administer "hudson.model.Hudson.Administer"
   :hudson-read "hudson.model.Hudson.Read"
   :item-build "hudson.model.Item.Build"
   :item-configure "hudson.model.Item.Configure"
   :item-create "hudson.model.Item.Create"
   :item-delete "hudson.model.Item.Delete"
   :item-read "hudson.model.Item.Read"
   :item-workspace "hudson.model.Item.Workspace"
   :run-delete "hudson.model.Run.Delete"
   :run-update "hudson.model.Run.Update"
   :scm-tag "hudson.scm.SCM.Tag"
   :view-configure "hudson.model.View.Configure"
   :view-create "hudson.model.View.Create"
   :view-delete "hudson.model.View.Delete"})

(def all-permissions
  [:computer-configure :computer-delete :hudson-administer :hudson-read
   :item-build :item-configure :item-create :item-delete :item-read
   :item-workspace :run-delete :run-update :scm-tag :view-configure
   :view-create :view-delete])

(defn config-xml
  "Generate config.xml content"
  [node-type options]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *config-file*) node-type
    [options]
    [:useSecurity] (xml/content (if (:use-security options) "true" "false"))
    [:securityRealm] (when-let [realm (:security-realm options)]
                       (xml/set-attr :class (security-realm-class realm)))
    [:disableSignup] (xml/content
                      (if (:disable-signup options) "true" "false"))
    [:authorizationStrategy] (when-let [strategy (:authorization-strategy
                                                  options)]
                               (xml/set-attr
                                :class (authorization-strategy-class strategy)))
    [:permission] (xml/clone-for
                   [permission (apply
                                concat
                                (map
                                 (fn user-perm [user-permissions]
                                   (map
                                    #(hash-map
                                      :user (:user user-permissions)
                                      :permission (permission-class % %))
                                    (:permissions user-permissions)))
                                 (:permissions options)))]
                   (xml/content
                    (format "%s:%s"
                            (:permission permission) (:user permission)))))
   options))

(defn config
  "hudson config."
  [request & {:keys [use-security security-realm disable-signup
                     admin-user admin-password] :as options}]
  (let [group (parameter/get-for-target request [:hudson :group])
        hudson-owner (parameter/get-for-target request [:hudson :owner])
        hudson-data-path (parameter/get-for-target
                          request [:hudson :data-path])]
    (-> request
        (parameter/assoc-for-target
         [:hudson :admin-user] admin-user
         [:hudson :admin-password] admin-password)
        (remote-file
         (format "%s/config.xml" hudson-data-path)
         :content (config-xml (:node-type request) options)
         :owner hudson-owner :group group :mode "0664"))))
