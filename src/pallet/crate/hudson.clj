(ns pallet.crate.hudson
 "Installation of hudson"
  (:use
   [pallet.crate.tomcat :only [tomcat-deploy tomcat-policy tomcat-application-conf]]
   [pallet.utils :only [cmd-join]]
   [pallet.resource :only [defcomponent]]
   [pallet.resource.service :only [service]]
   [pallet.resource.directory :only [directory directory*]]
   [pallet.resource.remote-file :only [remote-file remote-file*]]
   [pallet.resource.user :only [user]]
   [clojure.contrib.prxml :only [prxml]]
   [clojure.contrib.logging]))

(def hudson-data-path "/var/hudson")
(def hudson-owner "root")
(def hudson-group "tomcat6")

(defn hudson
  "Install hudson"
  []
  (let [file (str hudson-data-path "/hudson.war")]
    (directory hudson-data-path :owner hudson-owner :group hudson-group :mode "775")
    (remote-file file
     :url "http://hudson-ci.org/latest/hudson.war"
     :md5  "680e1525fca0562cfd19552b8d8174e2")
    (tomcat-policy
     99 "hudson"
     {(str "file:${catalina.base}/webapps/hudson/-")
      ["permission java.security.AllPermission"]
      (str "file:" hudson-data-path "/-")
      ["permission java.security.AllPermission"]})
    (tomcat-application-conf
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
    (tomcat-deploy file "hudson")))

(def hudson-plugins
     {:git "https://hudson.dev.java.net/files/documents/2402/135478/git.hpi"})

(defn hudson-plugin*
  [plugin & options]
  (let [opts (apply hash-map options)]
    (remote-file*
     (str hudson-data-path "/plugins/" (name plugin) ".hpi")
     :url (or (opts :url) (hudson-plugins plugin)))))

(defcomponent hudson-plugin
  "Install a hudson plugin.  The plugin should be a keyword.
  :url can be used to specify a string containing the download url"
  hudson-plugin* [plugin & options])

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
  (fn [scm-type scm-path options] scm-type))

(defmethod output-scm-for :git [scm-type scm-path options]
  (prxml
   [:org.spearce.jgit.transport.RemoteConfig
    [:string (get options :name "origin")]
    [:int 5]
    [:string "fetch"]
    [:string (get options :refspec "+refs/heads/*:refs/remotes/origin/*")]
    [:string "receivepack"]
    [:string (get options :receivepack "git-upload-pack")]
    [:string "uploadpack"]
    [:string (get options :uploadpack "git-upload-pack")]
    [:string "url"]
    [:string scm-path]
    [:string "tagopt"]
    [:string (get options :tagopt "")]]))

(defn normalise-scms [scms]
  (map #(if (string? %) [%] %) scms))

(def class-for-scm
     { :git "hudson.plugins.git.GitSCM"})

(defmulti output-build-for
  "Output the build definition for specified type"
  (fn [build-type scm-type scms options] build-type))

(defmethod output-build-for :maven2
  [build-type scm-type scms options]
  (let [scm-type (or scm-type (some determine-scm-type scms))]
    (with-out-str
      (prxml
       [:maven2-moduleset
        [:scm { :class (class-for-scm scm-type)}
         [:remoteRepositories
          (map #(output-scm-for
                 scm-type
                 (first %)
                 (if (seq (next %)) (apply hash-map %) {}))
               scms)]
         [:branches
          [:hudson.plugins.git.BranchSpec
           (map #(vector :name %) (get options :branches ["origin/master"]))]]]
        (map #(vector (first %) (second %)) options)]))))


(defn hudson-job*
  [build-type name & options]
  (let [opts (apply hash-map options)]
    (cmd-join
     [(directory* (str hudson-data-path "/jobs/" name) :p true)
      (remote-file*
       (str hudson-data-path "/jobs/" name "/config.xml" )
       :content
       (output-build-for
        build-type
        (opts :scm-type)
        (normalise-scms (opts :scm))
        (dissoc opts :scm :scm-type)))
      (directory*
       hudson-data-path
       :owner hudson-owner :group hudson-group
       :mode "g+w"
       :recursive true)])))

(defcomponent hudson-job
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
  hudson-job* [build-type name & options])

