(ns pallet.crate.squeak
  "Crate for working with squeak, pharo and seaside.
   This is by not finished as yet."
  (:require
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.package :as package]
   [pallet.resource.directory :as directory]
   [pallet.resource.file :as file]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.remote-directory :as remote-directory]
   [pallet.crate.upstart :as upstart]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string])
  (:use
   pallet.thread-expr))

(defn squeak-vm
  "Install squeak VM package.
   The :smalltalk :vm keys are used to hold a function which will generate
   script to launch squeak-vm with the arguments it is passed."
  [request]
  ;; the package on debian sucks.  no simple squeakvm wrapper.
  ;; no way of overwridding options without setting the vm.
  (->
   request
   (package/package "squeak-vm")
   (parameter/assoc-for-target
    [:smalltalk :vm] (fn [& args]
                       (stevedore/script
                        (export (str VM_VERSION=
                                     @(pipe
                                       (find "/usr/lib/squeak/" -name "squeakvm"
                                             -type f)
                                       (cut -f5 "-d\"/\""))))
                        (export SQ_DIR=/usr/lib/squeak/$VM_VERSION)
                        ((str @SQ_DIR "/squeakvm")
                         -vm-sound-null -vm-display-null ~@args))))))


(def ^{:doc "Default install location"} pharo-dir
  "/opt/local/pharo")

(def ^{:doc "Download url"} pharo-url
  {:stable "http://www.pharo-project.org/pharo-download/stable"})

(defn pharo-md5s
  [version]
  {:stable nil})

;;; build smalltalk statements
(defmulti smalltalk-message class)
(defmethod smalltalk-message clojure.lang.Symbol
  [s]
  (name s))

(defmethod smalltalk-message clojure.lang.Keyword
  [s]
  (name s))

(defmethod smalltalk-message clojure.lang.IPersistentMap
  [m]
  (string/join " " (map #(format "%s: %s" (name (key %)) (val %)) m)))

(defn smalltalk-statement
  [object msgs]
  (str object " "
       (string/join ";\n" (map smalltalk-message (filter identity msgs))) "."))

(defn smalltalk-call
  "Convert a map to smalltalk"
  [object & {:as message}]
  (str "(" object (smalltalk-message message) ")"))

(defn smalltalk
  "Convert a map to smalltalk"
  [object & messages]
  (str (smalltalk-statement object messages) "\n"))

(defn smalltalk-str [arg]
  (str "'" arg "'"))

(defn smalltalk-comment [arg]
  (str "\"" arg "\"\n"))

;;; Pharo
(defn pharo
  "Install pharo smalltalk."
  [request & {:keys [version name enable-preferences disable-preferences
                     install-dir new-image-name]
              :or {version :stable
                   name "noname"
                   install-dir pharo-dir
                   }}]
  (let [image (str install-dir "/Pharo-*.image")]
    (->
     request
     (package/package "unzip")
     (remote-directory/remote-directory
      install-dir :url (pharo-url version) :md5 (pharo-md5s version)
      :unpack :unzip :unzip-options "-j -o")
     (parameter/assoc-for-target
      [:pharo :install-dir] install-dir
      [:pharo :image] image))))


(defn pharo-preferences
  "Generate smalltalk for setting the given preferences."
  [& {:keys [enable disable]}]
  (str
   (smalltalk-comment "Set Preferences")
   (string/join
    (map #(format "Preferences enable: %s.\n" %) enable))
   (string/join
    (map #(format "Preferences disable: %s.\n" %) disable))))

(defn pharo-author
  [your-name]
  (str
   (smalltalk-comment "Your name")
   (smalltalk "Author" {:fullName (smalltalk-str your-name)})))

(defn pharo-installer
  []
  (str "\"Install Installer\"\n"
       (smalltalk "ScriptLoader"
                  {:loadLatestPackage "'Installer-Core'"
                   :fromSqueaksource "'Installer'"})))
(defn pharo-update
  "Update from pharo update stream"
  []
  "[Utilities updateFromServer] valueSuppressingAllMessages."
  ;; (str (smalltalk-comment "Update from pharo update stream")
  ;;      (smalltalk "Utilities" :updateFromServer))
  )

(defn pharo-save-and-snapshot-image
  [image-name & {:keys [snapshot quit] :or {snapshot true quit true}}]
  (str
   (smalltalk-comment (format "Save image %s" image-name))
   (smalltalk "SmalltalkImage current" {:saveAs (smalltalk-str image-name)})
   (smalltalk "SmalltalkImage current" {:snapshot snapshot :andQuit quit})))

(defn pharo-script
  "Run a script with the given content. Content options are as for remote-file."
  [request {:keys [image] :as options}]
  (let [vm (parameter/get-for-target request [:smalltalk :vm])
        image (parameter/get-for-target request [:pharo :image])
        install-dir (parameter/get-for-target request [:pharo :install-dir])]
    (->
     request
     (exec-script/exec-script
      (cd ~install-dir)
      (var pharo_script (str ~install-dir "/" (make-temp-file "pharo"))))
     (apply->
      remote-file/remote-file
      (stevedore/script @pharo_script)
      :no-versioning true
      (apply
       concat
       (merge {:literal true} options)))
     (exec-script/exec-checked-script
      "Pharo script"
      ~(vm (str install-dir "/" image) (stevedore/script @pharo_script)))
     (remote-file/remote-file
      (stevedore/script @pharo_script)
      :action :delete))))


(defn clean-up-for-production []
  (smalltalk "ScriptLoader new" :cleanUpForProduction))

(defn gofer-magma
  [load]
  (str
   (smalltalk "Gofer new"
              {:squeaksource "'MetaRepoForPharo11'"}
              {:package "'ConfigurationOfMagma'"}
              :load)
   (case load
     :client (smalltalk
              "ConfigurationOfMagma project latestVersion"
              {:load "'Client'"})
     :server (smalltalk
              "ConfigurationOfMagma project latestVersion"
              {:load "'Server'"})
     :tester (smalltalk
              "ConfigurationOfMagma project latestVersion"
              {:load "'Tester'"}))))

(defn gofer-rfb
  []
  (smalltalk "Gofer new"
             {:renggli "'unsorted'"}
             {:package "'RFB'"}
             :load))

(defn gofer-seaside
  []
  (str
   (smalltalk "Gofer new"
              {:squeaksource (smalltalk-str "MetacelloRepository")}
              {:package (smalltalk-str "ConfigurationOfSeaside30")}
              :load)
   "((Smalltalk at: #ConfigurationOfSeaside30 ) project latestVersion)
     load: #('Base' 'Seaside-Adaptors-Comanche' 'JQuery-UI' 'Seaside-Examples')."
   "(WAComancheAdaptor port: 8080) start."))

;;; It seems Installer is deprecated
;; scripts adapted from
;; http://miguel.leugim.com.mx/index.php/2009/09/22/deploying-seaside-prepare-the-images/

(defn pharo-package
  "Create smalltalk to install a package on pharo"
  [installer project & packages]
  (apply
   smalltalk (format "Installer %s" installer)
   {:project (smalltalk-str project)}
   (map #(array-map :install (smalltalk-str %)) packages)))


(defn install-magma
  "Install magma server."
  []
  (pharo-package "ss" "Magma" "1.0r42 (server)"))

(defn install-rfb
  "Install rfb"
  [& {:keys [allow-empty-passwords allow-local-connections
             allow-remote-connections
             allow-interactive-connections
             connection-type-disconnect
             configure-for-memory-conservation
             set-full-password]
      :or {allow-empty-passwords false
           allow-local-connections true
           allow-remote-connections false
           allow-interactive-connections true
           connection-type-disconnect true
           configure-for-memory-conservation true}}]
  (str
   (pharo-package "lukas" "unsorted" "RFB")
   (smalltalk-comment "Configure RFB")
   (smalltalk
    "RFBServer current"
    :initializePreferences
    {:allowEmptyPasswords allow-empty-passwords}
    {:allowLocalConnections allow-local-connections}
    {:allowRemoteConnections allow-remote-connections}
    {:allowInteractiveConnections true}
    (when connection-type-disconnect
      :connectionTypeDisconnect)
    (when configure-for-memory-conservation
      :configureForMemoryConservation)
    (when set-full-password
      {:setFullPassword (smalltalk-str set-full-password)}))))

(defn install-comanche
  [& {:keys [port unregister] :or {port 9001}}]
  (str
   (smalltalk-comment "Install Commanche")
   (pharo-package
    "ss" "KomHttpServer" "DynamicBindings" "KomServices" "KomHttpServer")
   (smalltalk "WAKom" {:startOn port})
   (when unregister
     (str
      (smalltalk "WADispatcher default" :trimForDeployment)
      (smalltalk "WADispatcher default"
                 {:unregister
                  (smalltalk-call "WADispatcher default"
                                  :entryPointAt (smalltalk-str "/browse"))}
                 {:unregister
                  (smalltalk-call "WADispatcher default"
                                  :entryPointAt (smalltalk-str "/config"))})))))

(defn install-seaside
  [username password & {:keys [version] :or {version "Seaside2.8a1"}}]
  (str
   (smalltalk-comment "Install Seaside")
   (smalltalk
    "Installer ss"
    {:project "Seaside"}
    {:answer (smalltalk-str ".*username.*") :with (smalltalk-str username)}
    {:answer (smalltalk-str ".*password.*") :with (smalltalk-str password)}
    {:install (smalltalk-str version)}
    (:install (smalltalk-str "Scriptaculous")))))

(defn install-seaside-jetsam
  [& {:keys [version] :or {version "Seaside28Jetsam-kph.67"}}]
  (str
   (smalltalk-comment "Install Seaside")
   (pharo-package "ss" "Jetsam" version)))

(defn install-magma-seaside-helper
  [username password]
  (str
   (smalltalk-comment "Install Magma Seaside Helper")
   (smalltalk
    "Installer ss"
    {:project "MagmaTester"}
    {:answer (smalltalk-str ".*username.*") :with (smalltalk-str username)}
    {:answer (smalltalk-str ".*password.*") :with (smalltalk-str password)}
    (:install (smalltalk-str "Magma seasideHelper")))))

(defn install-seaside-proxy-tester
  []
  (str
   (smalltalk-comment "Install SeasideProxyTester")
   (pharo-package "ss" "SeasideExamples" "SeasideProxyTester")))

(defn magma-server-image
  "Build image for magma."
  [request]
  (let [magma (str
               (pharo-preferences
                :enable ["#fastDragWindowForMorphic" "#updateSavesFile."]
                :disable ["#windowAnimation"])
               (pharo-update)
               (pharo-author "MyName")
               (pharo-installer)
               (gofer-rfb)
               (gofer-magma :server)
               (pharo-save-and-snapshot-image "magma"))]
    (->
     request
     (pharo-script {:content magma}))))

(defn seaside-server-image
  "Build image for seaside"
  [request]
  (let [seaside (str
                 (pharo-preferences
                  :enable ["#fastDragWindowForMorphic" "#updateSavesFile."]
                  :disable ["#windowAnimation"])
                 ;(pharo-update)
                 (pharo-author "MyName")
                 (pharo-installer)
                 (gofer-seaside)
                 ;(install-comanche)
                 ;(install-seaside "admin" "password")
                 ;(install-seaside-jetsam)
                 ;(install-magma-seaside-helper "admin" "password")
                 ;(install-seaside-proxy-tester)
                 (pharo-save-and-snapshot-image "seaside"))]
    (->
     request
     (pharo-script {:content seaside}))))


(def magma-st
  "[
[
[ 60 seconds asDelay wait.
(FileDirectory default fileOrDirectoryExists: 'magma.shutdown')
ifTrue: [ SmalltalkImage current snapshot: false andQuit: true ].
(FileDirectory default fileOrDirectoryExists: 'magma.startvnc')
ifTrue: [ Project uiProcess resume.  RFBServer start:0 ].
(FileDirectory default fileOrDirectoryExists: 'magma.stopvnc')
ifTrue: [ RFBServer stop. Project uiProcess suspend ].
] on: Error do: [ :error | error asDebugEmail ]
] repeat
] forkAt: Processor systemBackgroundPriority.
\"To save CPU cycles\"
Project uiProcess suspend.")

(defn magma-service
  [request & {:keys [image log-dir]
              :or {image "magma.image"
                   log-dir "/var/log/magma"}}]
  (let [vm (parameter/get-for-target request [:smalltalk :vm])
        install-dir (parameter/get-for-target request [:pharo :install-dir])
        image (str install-dir "/" image)
        script (str install-dir "/magma.st")
        files (map #(str install-dir "/" %)
                   ["magma.shutdown" "magma.startvnc" "magma.stopvnc"])]
    (->
     request
     (remote-file/remote-file script :content magma-st :literal true)
     (directory/directory log-dir)
     (pallet.crate.upstart/job
      "magma"
      :pre-start-script (stevedore/chain-commands*
                         (map
                          #(stevedore/script (if (file-exists? %) ("rm" -f %)))
                          files))
      :script (stevedore/script
               ~(vm image  ">>" (str log-dir "/magma.out")))
      :pre-stop-script (stevedore/script
                        (touch ~(first files)))))))


(def seaside-st
  "[
[
[ 60 seconds asDelay wait.
(FileDirectory default fileOrDirectoryExists: 'seaside.shutdown')
ifTrue: [ SmalltalkImage current snapshot: false andQuit: true ]
] on: Error do: [ :error | error asDebugEmail ]
] repeat
] forkAt: Processor systemBackgroundPriority.
“To save CPU cycles”
Project uiProcess suspend.")

(defn seaside-service
  [request & {:keys [image log-dir from-port to-port]
              :or {image "seaside.image"
                   log-dir "/var/log/seaside"
                   from-port 9001
                   to-port 9004}}]
  (let [vm (parameter/get-for-target request [:smalltalk :vm])
        install-dir (parameter/get-for-target request [:pharo :install-dir])
        image (str install-dir "/" image)
        script (str install-dir "/seaside.st")
        files (map #(str install-dir "/" %) ["seaside.shutdown"])]
    (->
     request
     (remote-file/remote-file script :content seaside-st :literal true)
     (directory/directory log-dir)
     (pallet.crate.upstart/job
      "seaside"
      :pre-start-script (stevedore/chain-commands*
                         (map
                          #(stevedore/script (if (file-exists? %) ("rm" -f %)))
                          files))
      :script (stevedore/chain-commands*
               (map #(stevedore/script
                      ~(vm image "port" %
                           ">>" (str log-dir "/seaside.out")))
                    (range from-port to-port)))
      :pre-stop-script (stevedore/script
                        (touch ~(first files)))))))

#_
(pallet.core/defnode a
  {}
  :bootstrap (pallet.resource/phase
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.resource/phase
              (pallet.crate.squeak/squeak-vm)
              (pallet.crate.squeak/pharo)
              (pallet.crate.squeak/magma-server-image)
              (pallet.crate.squeak/seaside-server-image)
              (pallet.crate.squeak/magma-service)
              (pallet.crate.squeak/seaside-service)))
