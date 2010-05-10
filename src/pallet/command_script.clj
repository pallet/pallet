(ns pallet.command-script
  "Produce a shell script for launching Pallet, possibly customised for extra jars"
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.resource.user :as user]))

(defn path
  [components]
  (apply str (interpose "/" components)))

(defn normalize-scriptname
  "normalize $0 on certain BSDs"
  []
  (stevedore/script
   (if (= @(dirname $0) ".")
     (defvar SCRIPT "$(which $(basename $0))")
     (defvar SCRIPT "$0"))))

(defn resolve-symlinks
  "resolve symlinks to the script itself portably"
  []
  (stevedore/script
   (while (symlink? @SCRIPT)
          (defvar ls @(ls -ld (quoted @SCRIPT)))
          (defvar link @(expr (quoted @ls) ":" "'.*-> \\(.*\\)$'"))
          (if (expr (quoted @link) ":" "'/.*'" > "/dev/null")
            (defvar SCRIPT (quoted @link))
            (defvar SCRIPT (quoted (str @(dirname @SCRIPT) "/" @link)))))
   (defvar BIN_DIR (quoted @(dirname (quoted @SCRIPT))))))

(defn http-client
  "Script to set HTTP client options."
  []
  (stevedore/script
   (defvar HTTP_CLIENT (quoted "wget "))
   (defvar HTTP_OUTFILE (quoted "-O "))
   (defvar HTTP_STDOUT (quoted "-q -O - "))
   (if (type -p curl ">/dev/null 2>&1")
     (do
       (defvar HTTP_CLIENT (quoted "curl -L "))
       (defvar HTTP_OUTFILE (quoted "-o "))
       (defvar HTTP_STDOUT (quoted ""))))))

(defn defn-snapshot-path
  [artifacts]
  (stevedore/script
   (defn snapshot-path [version metafile base_path]
     (defvar METADATA
       @(@HTTP_CLIENT @HTTP_STDOUT @metafile))
     (defvar JARDATE
       @(pipe
         (echo @META)
         (egrep (quoted "[0-9]{8}\\.[0-9]{6}") -o)))
     (defvar JARBUILD
       @(pipe
         (echo @META)
         (fgrep "buildNumber")
         (egrep (quoted "[0-9]+") -o)))
     (defvar JARVERSION
       ~(.replace (:version (first artifacts)) "-SNAPSHOT" ""))
     (println (str @base_path @JARVERSION "-" @JARDATE "-" @JARBUILD ".jar")))))

(defprotocol Artifact
  (local-path [x] "Local path for installing the artifact")
  (remote-path [x] "Remote path for downloading the artifact")
  (jar-name [x] "jar name for the artifact"))

(defrecord MavenArtifact
  [group-id artifact-id version repository]
  Artifact
  (local-path
   [artifact]
   (path [(stevedore/script @repo) (.replace group-id "." "/") artifact-id version
          (jar-name artifact)]))
  (remote-path
   [artifact]
   (if (.contains version "SNAPSHOT")
     (let [metafile (path [repository (.replace group-id "." "/") artifact-id version
                           "maven-metadata.xml"])]
       (stevedore/script
        @(snapshot-path
          ~version
          ~metafile
          ~(path [repository group-id (.replace group-id "." "/") version
                  (str artifact-id "-")]))))
     (path [repository (.replace group-id "." "/") artifact-id version
            (jar-name artifact)])))
  (jar-name
   [_]
   (str artifact-id "-" version ".jar")))

(defn m2-repository-path
  "Return a script fragment setting $repo to the local maven repository path"
  []
  (stevedore/script
   (defvar settings (str @HOME "/.m2/settings.xml"))
   (defvar default_repo (str @HOME "/.m2/repository"))
   (if (file-exists? @settings)
     (do
       (defvar repo
         @(chain-or
           (group
            (pipe
             (cat @settings)
             (tr -d "'\n\t '")
             (egrep -o (quoted "<localRepository>(.*)</localRepository>"))
             (sed -e (quoted "s%\\${user.home}%${HOME}%")
                  -e (quoted "s%<localRepository>%%")
                  -e (quoted "s%</localRepository>%%"))))
           (println @default_repo)))
       (if (= @repo "")
         (defvar repo @default_repo)))
     (defvar repo (str @user-home "/.m2/repository")))))

(defn download-artifact
  [artifact]
  (stevedore/script
   (@HTTP_CLIENT $HTTP_OUTFILE
                 (quoted ~(local-path artifact))
                 (quoted ~(remote-path artifact)))))

(defn defn-download
  "Produces a function to unconditionaly download artifacts"
  [artifacts]
  (stevedore/script
   (defn do-download []
     ~(apply stevedore/checked-commands
       "Downloading dependencies"
       (map download-artifact artifacts)))))

(defn defn-install
  []
  (stevedore/script
   (defn do-install []
     (do-download)
     (exit 0))))

(defn defn-upgrade
  [artifacts]
  (stevedore/script
   (defn do-upgrade []
     (if-not (writeable? $SCRIPT)
       (do
         (println "You do not have permission to upgrade the installation in "
                  @SCRIPT)
         (exit 1)))
     (echo
      "The script at " @SCRIPT " will be upgraded to the latest stable version.")
     (echo -n "Do you want to continue [Y/n]? ")
     (read RESPONSE)
     (case @RESPONSE
         "y|Y|\"\"" (do
                      (println)
                      (println "Upgrading...")
                      (defvar BRANCH
                        ~(if (.contains (:version (first artifacts)) "SNAPSHOT")
                           "master"
                           "stable"))
                      (defvar PALLET_SCRIPT_URL
                        (quoted
                         (str "http://github.com/hugoduncan/pallet/raw/"
                              @BRANCH
                              "/bin/pallet")))
                      (chain-and
                       (@HTTP_CLIENT
                        $HTTP_OUTFILE
                        (quoted @SCRIPT) (quoted @PALLET_SCRIPT_URL))
                       ("chmod" +x (quoted @SCRIPT))
                       (println)
                       (@SCRIPT self-install)
                       (println)
                       (println "Now running" @(@SCRIPT version)))
                      (exit "$?"))
         * (do
             (println "Aborted")
             (exit 1))))))

(defn run-from-checkout
  []
  (stevedore/script
   (defvar PALLET_DIR
     (quoted @(dirname (quoted @BIN_DIR))))
   (defvar PALLET_LIBS
     (quoted
      @(pipe
        (find -H (str @PALLET_DIR "/lib") -mindepth 1 -maxdepth 1 -print0
              "2> /dev/null")
        (tr "\\\\0" "\\:"))))
   (defvar CLASSPATH
     (quoted (str (str @PALLET_DIR "/src") ":" (str @PALLET_DIR "/pallet/src") ":" @PALLET_LIBS ":" @CLASSPATH)))
   (defvar BOOTPATH (quoted ""))
   (if (&& (= @PALLET_LIBS "") (!= "$1" "self-install" ))
     (do
       (println "Your Pallet development checkout is missing its dependencies.")
       (println "Please use you maven or lein to download the dependencies.")
       (println (quoted "   cd " @PALLET_DIR))
       (println (quoted " and either:"))
       (println (quoted "   lein deps"))
       (println (quoted "   mvn -Dmaven.test.skip=true assembly:directory"))
       (exit 1)))))

;; We want to run from the first of:
;;   nested pallet project
;;   current project
;;   mvn repo
(defn run-from-jar
  [artifacts]
  (stevedore/script
   (if (readable? (str "./pallet/lib/" ~(jar-name (first artifacts)) ))
     (do
       (defvar PALLET_LIBS
         (quoted
          @(pipe
            (find -H "./pallet/lib/" -mindepth 1 -maxdepth 1 -print0
                  "2> /dev/null")
            (tr "\\\\0" "\\:"))))
       (defvar CLASSPATH
         (quoted (str @PALLET_LIBS ":./pallet/src/:" @CLASSPATH)))
       (defvar BOOTPATH (quoted "")))
     (if (readable? (str "./lib/" ~(jar-name (first artifacts)) ))
       (do
         (defvar PALLET_LIBS
         (quoted
          @(pipe
            (find -H "./lib/" -mindepth 1 -maxdepth 1 -print0 "2> /dev/null")
            (tr "\\\\0" "\\:"))))
         (defvar CLASSPATH
           (quoted (str @PALLET_LIBS ":./pallet/src/:" @CLASSPATH)))
         (defvar BOOTPATH (quoted "")))
       (do
         (defvar CLASSPATH
           (quoted
            (str
             ~(apply
               str (interpose ":" (map local-path artifacts)))
             ":" @CLASSPATH))))))
   (defvar BOOTPATH (quoted ""))))


(defn paths-for-cygwin
  []
  (stevedore/script
   (when (type -p cygpath ">/dev/null 2>&1")
     (defvar CLOJURE_JAR
       @(cygpath -w (quoted @CLOJURE_JAR)))
     (defvar CLASSPATH
       @(cygpath -w (quoted @CLASSPATH))))))

(defn process-commands
  [artifacts]
  (stevedore/script
   (if (= "$1" "self-install")
     (do-install))
   (if (= "$1" "upgrade")
     (do-upgrade))
   (if (readable? (str @BIN_DIR "/../src/pallet/core.clj"))
     (do ~(run-from-checkout))
     (do ~(run-from-jar artifacts)))
   ~(paths-for-cygwin)
   (if "[ $DEBUG ]"
     (println @CLASSPATH))
   (exec
    @RLWRAP
    java -client @JAVA_OPTS -cp (quoted @CLASSPATH)
    (str "-Dpallet.version=" ~(:version (first artifacts)))
    @JLINE
    clojure.main -e (quoted "(use 'pallet.main)(-main)")
    "/dev/null"
    "$@")))

(defn command-script
  [pallet-version artifacts]
  (stevedore/do-script
   "#!/usr/bin/env bash"
   (normalize-scriptname)
   (resolve-symlinks)
   (http-client)
   (m2-repository-path)
   (defn-snapshot-path artifacts)
   (defn-download artifacts)
   (defn-upgrade artifacts)
   (defn-install)
   (process-commands artifacts)))

(def clojars-repo "http://clojars.org/repo")
(def clojure-release-repo "http://build.clojure.org/releases/")
(def clojure-snapshot-repo "http://build.clojure.org/snapshots")

(defn write-script
  []
  (println
   (command-script
    "0.0.1-SNAPSHOT"
    [(MavenArtifact. "pallet" "pallet" "0.0.1-SNAPSHOT" clojars-repo)
     (MavenArtifact. "org.clojure" "clojure" "1.2.0-master-SNAPSHOT"
                     clojure-snapshot-repo)])))
