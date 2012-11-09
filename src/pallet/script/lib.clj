(ns pallet.script.lib
  "Script library for abstracting target host script differences"
  (:require
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [clojure.string :as string]))

;;; basic
(defn- translate-options
  [options translations]
  (reduce
   (fn [options [from to]]
     (-> options
         (assoc to (from options))
         (dissoc from)))
   options
   translations))

(script/defscript exit [value])
(script/defimpl exit :default [value]
  (exit ~value))

(script/defscript xargs [script])
(script/defimpl xargs :default
  [script]
  (xargs ~script))

(script/defscript which [arg])
(script/defimpl which :default
  [arg]
  (which ~arg))

(script/defscript has-command?
  "Check whether the specified command is on the path"
  [arg])
(script/defimpl has-command? :default [arg] (hash ~arg "2>&-"))

(script/defscript canonical-path [path]
  "Return the canonical version of the specified path"
  [arg])
(script/defimpl canonical-path :default [arg] (readlink -f ~arg))
(script/defimpl canonical-path [#{:darwin :os-x}] [arg]
  (chain-and
   (var ccwd @(pwd))
   (cd @(dirname ~arg))
   (println (quoted (str @(pwd -P) "/" @(basename ~arg))))
   (cd @ccwd)))


(script/defscript rm [file & {:keys [recursive force]}])
(script/defimpl rm :default [file & {:keys [recursive force] :as options}]
  ("rm" ~(stevedore/map-to-arg-string options) ~file))
(script/defimpl rm [#{:darwin :os-x}] [file & {:keys [recursive force]}]
  ("rm" ~(stevedore/map-to-arg-string {:r recursive :f force}) ~file))

(script/defscript mv [source destination & {:keys [force backup]}])
(script/defimpl mv :default
  [source destination & {:keys [force backup]}]
  (mv
   ~(stevedore/map-to-arg-string
     {:f force :backup (when backup (name backup))}
     :assign true)
   ~source ~destination))
(script/defimpl mv [#{:darwin :os-x}]
  [source destination & {:keys [force backup]}]
  (mv
   ~(stevedore/map-to-arg-string
     {:f force}
     :assign true)
   ~source ~destination))

(script/defscript cp [source destination & {:keys [force backup preserve]}])
(script/defimpl cp :default
  [source destination & {:keys [force backup preserve]}]
  (cp
   ~(stevedore/map-to-arg-string {:f force
                                  :backup (when backup (name backup))
                                  :p preserve})
   ~source ~destination))
(script/defimpl cp [#{:darwin :os-x}]
  [source destination & {:keys [force backup preserve]}]
  (cp
   ~(stevedore/map-to-arg-string {:f force :p preserve})
   ~source ~destination))

(script/defscript ln [source destination & {:keys [force symbolic]}])
(script/defimpl ln :default
  [source destination & {:keys [force symbolic]}]
  (ln
   ~(stevedore/map-to-arg-string {:f force :s symbolic})
   ~source ~destination))

(script/defscript backup-option [])
(script/defimpl backup-option :default []
  "--backup=numbered")
(script/defimpl backup-option [#{:darwin :os-x}] []
  "")

(script/defscript basename [path])
(script/defimpl basename :default
  [path]
  (basename ~path))

(script/defscript dirname [path])
(script/defimpl dirname :default
  [path]
  (dirname ~path))

(script/defscript ls [pattern & {:keys [sort-by-time sort-by-size reverse]}])
(script/defimpl ls :default
  [pattern & {:keys [sort-by-time sort-by-size reverse]}]
  (ls ~(stevedore/map-to-arg-string
          {:t sort-by-time
           :S sort-by-size
           :r reverse})
   ~pattern))

(script/defscript cat [pattern])
(script/defimpl cat :default
  [pattern]
  (cat ~pattern))

(script/defscript tail [pattern & {:keys [max-lines]}])
(script/defimpl tail :default
  [pattern & {:keys [max-lines]}]
  (tail ~(stevedore/map-to-arg-string {:n max-lines}) ~pattern))

(script/defscript diff [file1 file2 & {:keys [unified]}])
(script/defimpl diff :default
  [file1 file2 & {:keys [unified]}]
  (diff ~(stevedore/map-to-arg-string {:u unified}) ~file1 ~file2))

(script/defscript cut [file & {:keys [fields delimiter]}])
(script/defimpl cut :default
  [file & {:keys [fields delimiter]}]
  (cut
   ~(stevedore/map-to-arg-string {:f fields :d delimiter})
   ~file))

(script/defscript chown [owner file & {:as options}])
(script/defimpl chown :default [owner file & {:as options}]
  (chown ~(stevedore/map-to-arg-string options) ~owner ~file))

(script/defscript chgrp [group file & {:as options}])
(script/defimpl chgrp :default [group file & {:as options}]
  (chgrp ~(stevedore/map-to-arg-string options) ~group ~file))

(script/defscript chmod [mode file & {:as options}])
(script/defimpl chmod :default [mode file & {:as options}]
  (chmod ~(stevedore/map-to-arg-string options) ~mode ~file))

(script/defscript touch [file & {:as options}])
(script/defimpl touch :default [file & {:as options}]
  (touch ~(stevedore/map-to-arg-string options) ~file))

(script/defscript md5sum [file & {:as options}])
(script/defimpl md5sum :default [file & {:as options}]
  (md5sum ~(stevedore/map-to-arg-string options) ~file))
(script/defimpl md5sum [#{:darwin :os-x}] [file & {:as options}]
  ("/sbin/md5" -r ~file))

(script/defscript normalise-md5
  "Normalise an md5 sum file to contain the base filename"
  [file])
(script/defimpl normalise-md5 :default
  [file]
  (if (egrep "'^[a-fA-F0-9]+$'" ~file)
    (echo
     (quoted (str "  " @(pipe (basename ~file) (sed -e "s/.md5//"))))
     ">>" ~file)))

(script/defscript md5sum-verify [file & {:as options}])
(script/defimpl md5sum-verify :default
  [file & {:keys [quiet check] :or {quiet true check true} :as options}]
  ("(" (chain-and
        (cd @(dirname ~file))
        (md5sum
         ~(stevedore/map-to-arg-string {:quiet quiet :check check})
         @(basename ~file))) ")"))
(script/defimpl md5sum-verify [#{:centos :debian :amzn-linux :rhel :fedora}]
  [file & {:keys [quiet check] :or {quiet true check true} :as options}]
  ("(" (chain-and
        (cd @(dirname ~file))
        (md5sum
         ~(stevedore/map-to-arg-string {:status quiet :check check})
         @(basename ~file))) ")"))
(script/defimpl md5sum-verify [#{:darwin :os-x}] [file & {:as options}]
  ("(" (chain-and
        (var testfile @(~cut ~file :delimiter " " :fields 2))
        (var md5 @(~cut ~file :delimiter " " :fields 1))
        (cd @(dirname ~file))
        ("test" (quoted @("/sbin/md5" -q @testfile)) == (quoted @md5))
        @mres) ")"))

(script/defscript backup-option [])
(script/defimpl backup-option :default []
  "--backup=numbered")
(script/defimpl backup-option [#{:darwin :os-x}] []
  "")

(script/defscript sed-file [file expr-map options])

(def ^{:doc "Possible sed separators" :private true}
  sed-separators
  (concat [\/ \_ \| \: \% \! \@] (map char (range 42 127))))

(script/defimpl sed-file :default
  [file expr-map {:keys [seperator restriction quote-with]
                  :or {quote-with "\""}
                  :as options}]
  (sed "-i"
   ~(if (map? expr-map)
      (string/join
       " "
       (map
        (fn [[key value]]
          (let [used (fn [c]
                       (or (>= (.indexOf key (int c)) 0)
                           (>= (.indexOf value (int c)) 0)))
                seperator (or seperator (first (remove used sed-separators)))]
            (format
             "-e %s%ss%s%s%s%s%s%s"
             quote-with
             (if restriction (str restriction " ") "")
             seperator key seperator value seperator quote-with)))
        expr-map))
      (format
       "-e %s%s%s%s"
       quote-with
       (if restriction (str restriction " ") "")
       expr-map quote-with))
   ~file))

(script/defscript download-file [url path & {:keys [proxy insecure]}])

(script/defimpl download-file :default [url path & {:keys [proxy insecure]}]
  (if (~has-command? curl)
    (curl "-o" (quoted ~path)
          --retry 5 --silent --show-error --fail --location
          ~(if proxy
             (let [url (java.net.URL. proxy)]
               (format "--proxy %s:%s" (.getHost url) (.getPort url)))
             "")
          ~(if insecure "--insecure" "")
          (quoted ~url))
    (if (~has-command? wget)
      (wget "-O" (quoted ~path) --tries 5 --no-verbose
            ~(if proxy
               (format
                "-e \"http_proxy = %s\" -e \"ftp_proxy = %s\"" proxy proxy)
               "")
            ~(if insecure "--no-check-certificate" "")
            (quoted ~url))
      (do
        (println "No download utility available")
        (~exit 1)))))

(script/defscript download-request [path request])
(script/defimpl download-request :default [path request]
  (curl "-o" (quoted ~path) --retry 3 --silent --show-error --fail --location
   ~(string/join
     " "
     (map (fn dlr-fmt [e] (format "-H \"%s: %s\"" (key e) (val e)))
          (:headers request)))
   (quoted ~(str (:endpoint request)))))

(script/defscript tmp-dir [])
(script/defimpl tmp-dir :default []
  @TMPDIR-/tmp)

(script/defscript make-temp-file [pattern])
(script/defimpl make-temp-file :default [pattern]
  @(mktemp (quoted ~(str pattern "XXXXX"))))

(script/defscript heredoc-in [cmd content {:keys [literal]}])
(script/defimpl heredoc-in :default [cmd content {:keys [literal]}]
  ("{" ~cmd
   ~(str (if literal "<<'EOFpallet'\n" "<<EOFpallet\n")
         content "\nEOFpallet\n }")))

(script/defscript heredoc [path content {:keys [literal]}])
(script/defimpl heredoc :default
  [path content {:keys [literal] :as options}]
  (~heredoc-in ("cat" ">" ~path) ~content ~options))

(script/defscript rmdir
  "Remove the specified directory"
  [directory & {:as options}])

(script/defimpl rmdir :default [directory & {:as options}]
  (rmdir ~(stevedore/map-to-arg-string options) ~directory))

(script/defscript mkdir
  "Create the specified directory"
  [directory & {:keys [path verbose mode]}])
(script/defimpl mkdir :default
  [directory & {:keys [path verbose mode] :as options}]
  (mkdir
   ~(stevedore/map-to-arg-string {:m mode :p path :v verbose})
   ~directory))

(script/defscript make-temp-dir
  "Create a temporary directory"
  [pattern & {:as options}])
(script/defimpl make-temp-dir :default [pattern & {:as options}]
  @(mktemp -d
    ~(stevedore/map-to-arg-string options)
    ~(str pattern "XXXXX")))


;;; Host information.

(script/defscript os-version-name [])
(script/defimpl os-version-name [#{:ubuntu :debian}] []
  @(lsb_release -c -s))

(script/defimpl os-version-name :default []
  "")

(script/defscript hostname [& options])
(script/defimpl hostname :default [& options]
  @(hostname
    ~(if (first options)
       (stevedore/map-to-arg-string (apply hash-map options))
       "")))

(script/defscript dnsdomainname [])
(script/defimpl dnsdomainname :default []
  @(dnsdomainname))

(script/defscript nameservers [])
(script/defimpl nameservers :default []
  @(grep nameserver "/etc/resolv.conf" | cut "-f2"))

(script/defscript debian-version [])
(script/defimpl debian-version :default []
  (if (file-exists? "/etc/debian") (cat "/etc/debian")))

(script/defscript redhat-version [])
(script/defimpl redhat-version :default []
  (if (file-exists? "/etc/redhat-release") (cat "/etc/redhat-release")))

(script/defscript ubuntu-version [])
(script/defimpl ubuntu-version :default []
  (if (file-exists? "/usr/bin/lsb_release") @("/usr/bin/lsb_release" -c -s)))

(script/defscript arch [])
(script/defimpl arch :default []
  @(uname -p))



;;; Users

(script/defscript user-exists? [name])

(script/defscript modify-user [name options])
(script/defscript create-user [name options])
(script/defscript remove-user [name options])
(script/defscript lock-user [name])
(script/defscript unlock-user [name])
(script/defscript user-home [username])
(script/defscript current-user [])

(script/defscript group-exists? [name])
(script/defscript modify-group [name options])
(script/defscript create-group [name options])
(script/defscript remove-group [name options])

(script/defimpl user-exists? :default [username]
  (getent passwd ~username))

(defn group-seq->string
  [groups]
  (if (not (string? groups))
    (string/join "," groups)
    groups))

(script/defimpl create-user :default [username options]
  ("/usr/sbin/useradd"
   ~(-> options
        (thread-expr/when->
         (:groups options)
         (update-in [:groups] group-seq->string))
        (thread-expr/when->
         (:group options)
         (assoc :g (:group options))
         (dissoc :group))
        stevedore/map-to-arg-string)
   ~username))

(script/defimpl create-user [#{:rhel :centos :amzn-linux :fedora}]
  [username options]
  ("/usr/sbin/useradd"
   ~(-> options
        (thread-expr/when->
         (:groups options)
         (update-in [:groups] group-seq->string))
        (translate-options {:system :r :group :g :password :p :groups :G})
        stevedore/map-to-arg-string)
   ~username))

(script/defimpl modify-user :default [username options]
  ("/usr/sbin/usermod"
   ~(stevedore/map-to-arg-string
     (-> options
         (thread-expr/when->
          (:groups options)
          (update-in [:groups] group-seq->string))))
   ~username))

(script/defimpl modify-user [#{:rhel :centos :amzn-linux :fedora}]
  [username options]
  ("/usr/sbin/usermod"
   ~(-> options
        (thread-expr/when->
         (:groups options)
         (update-in [:groups] group-seq->string))
        (translate-options
         {:system :r :group :g :password :p :append :a :groups :G})
        stevedore/map-to-arg-string)
   ~username))

(script/defimpl remove-user :default [username options]
  ("/usr/sbin/userdel" ~(stevedore/map-to-arg-string options) ~username))

(script/defimpl lock-user :default [username]
  ("/usr/sbin/usermod" --lock ~username))

(script/defimpl unlock-user :default [username]
  ("/usr/sbin/usermod" --unlock ~username))

(script/defimpl user-home :default [username]
  @("getent" passwd ~username | "cut" "-d:" "-f6"))

(script/defimpl user-home [#{:darwin :os-x}] [username]
  @(pipe
    ("dscl" localhost -read ~(str "/Local/Default/Users/" username)
          "dsAttrTypeNative:home")
    ("cut" -d "' '" -f 2)))

(script/defimpl current-user :default []
  @("whoami"))

(script/defimpl group-exists? :default [name]
  ("getent" group ~name))

(script/defimpl create-group :default [groupname options]
  ("/usr/sbin/groupadd" ~(stevedore/map-to-arg-string options) ~groupname))

(script/defimpl create-group [#{:rhel :centos :amzn-linux :fedora}]
  [groupname options]
  ("/usr/sbin/groupadd"
   ~(-> options
        (assoc :r (:system options))
        (dissoc :system)
        stevedore/map-to-arg-string)
   ~groupname))

(script/defimpl modify-group :default [groupname options]
  ("/usr/sbin/groupmod" ~(stevedore/map-to-arg-string options) ~groupname))

(script/defimpl remove-group :default [groupname options]
  ("/usr/sbin/groupdel" ~(stevedore/map-to-arg-string options) ~groupname))


;;; Package management


;;; the package management commands vary for each distribution, so we
;;; use a script multimethod to describe these
(script/defscript update-package-list
  "Update the list of packages available to the package manager from the
   declared package sources."
  [& options])

(script/defscript upgrade-all-packages
  "Upgrade the all installed package."
  [& options])

(script/defscript install-package
  "Install the specified package."
  [name & options])

(script/defscript upgrade-package
  "Upgrade the specified package."
  [name & options])

(script/defscript remove-package
  "Uninstall the specified package, leaving the configuration files if
   possible."
  [name & options])

(script/defscript purge-package
  "Uninstall the specified package, removing the configuration files if
   possible."
  [name & options])

(script/defscript list-installed-packages
  "List the installed packages"
  [& options])

;;; Implementation to do nothing
;;; Repeating the selector makes it more explicit
(script/defimpl update-package-list [#{:no-packages} #{:no-packages}]
  [& options] "")
(script/defimpl upgrade-all-packages [#{:no-packages} #{:no-packages}]
  [& options] "")
(script/defimpl install-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(script/defimpl upgrade-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(script/defimpl remove-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(script/defimpl purge-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(script/defimpl list-installed-packages [#{:no-packages} #{:no-packages}]
  [& options] "")

;;; aptitude
(script/defimpl update-package-list [#{:aptitude}] [& {:keys [] :as options}]
  (chain-or
   (aptitude update -q=2 -y ~(stevedore/map-to-arg-string options)) true))

(script/defimpl upgrade-all-packages [#{:aptitude}] [& options]
  (aptitude upgrade -q -y ~(stevedore/option-args options)))

(script/defimpl install-package [#{:aptitude}] [package & options]
  (aptitude install -q -y ~(stevedore/option-args options) ~package
            ;; show returns an error code if no package found, while install
            ;; does not.  There should be a better way than this...
            "&&" aptitude show ~package))

(script/defimpl upgrade-package [#{:aptitude}] [package & options]
  (aptitude install -q -y ~(stevedore/option-args options) ~package
            ;; show returns an error code if no package found, while install
            ;; does not.  There should be a better way than this...
            "&&" aptitude show ~package))

(script/defimpl remove-package [#{:aptitude}] [package & options]
  (aptitude remove -y ~(stevedore/option-args options) ~package))

(script/defimpl purge-package [#{:aptitude}] [package & options]
  (aptitude purge -y  ~(stevedore/option-args options) ~package))

(script/defimpl list-installed-packages [#{:aptitude}] [& options]
  (aptitude search (quoted "~i")))

;;; apt
(script/defimpl update-package-list [#{:apt}] [& {:keys [] :as options}]
  (apt-get -qq ~(stevedore/map-to-arg-string options) update))

(script/defimpl upgrade-all-packages [#{:apt}] [& options]
  (apt-get -qq -y ~(stevedore/option-args options) upgrade))

(script/defimpl install-package [#{:apt}] [package & options]
  (apt-get -qq -y ~(stevedore/option-args options) install ~package))

(script/defimpl upgrade-package [#{:apt}] [package & options]
  (apt-get -qq -y ~(stevedore/option-args options)  install ~package))

(script/defimpl remove-package [#{:apt}] [package & options]
  (apt-get -qq -y ~(stevedore/option-args options) remove ~package))

(script/defimpl purge-package [#{:apt}] [package & options]
  (apt-get -qq -y ~(stevedore/option-args options) remove ~package))

(script/defimpl list-installed-packages [#{:apt}] [& options]
  (dpkg --get-selections))

;;; yum
(script/defimpl update-package-list [#{:yum}] [& {:keys [enable disable]}]
  (yum makecache -q ~(string/join
                      " "
                      (concat
                       (map #(str "--disablerepo=" %) disable)
                       (map #(str "--enablerepo=" %) enable)))))

(script/defimpl upgrade-all-packages [#{:yum}] [& options]
  (yum update -y -q ~(stevedore/option-args options)))

(script/defimpl install-package [#{:yum}] [package & options]
  (yum install -y -q ~(stevedore/option-args options) ~package))

(script/defimpl upgrade-package [#{:yum}] [package & options]
  (yum upgrade -y -q ~(stevedore/option-args options) ~package))

(script/defimpl remove-package [#{:yum}] [package & options]
  (yum remove ~(stevedore/option-args options) ~package))

(script/defimpl purge-package [#{:yum}] [package & options]
  (yum purge ~(stevedore/option-args options) ~package))

(script/defimpl list-installed-packages [#{:yum}] [& options]
  (yum list installed))

;;; zypper
(script/defimpl update-package-list [#{:zypper}] [& options]
  (zypper refresh ~(stevedore/option-args options)))

(script/defimpl upgrade-all-packages [#{:zypper}] [& options]
  (zypper update -y ~(stevedore/option-args options)))

(script/defimpl install-package [#{:zypper}] [package & options]
  (zypper install -y ~(stevedore/option-args options) ~package))

(script/defimpl remove-package [#{:zypper}] [package & options]
  (zypper remove ~(stevedore/option-args options) ~package))

(script/defimpl purge-package [#{:zypper}] [package & options]
  (zypper remove ~(stevedore/option-args options) ~package))

;;; pacman
(script/defimpl update-package-list [#{:pacman}] [& options]
  (pacman -Sy "--noconfirm" "--noprogressbar"
   ~(stevedore/option-args options)))

(script/defimpl upgrade-all-packages [#{:pacman}] [& options]
  (pacman -Su "--noconfirm" "--noprogressbar" ~(stevedore/option-args options)))

(script/defimpl install-package [#{:pacman}] [package & options]
  (pacman -S "--noconfirm" "--noprogressbar"
   ~(stevedore/option-args options) ~package))

(script/defimpl upgrade-package [#{:pacman}] [package & options]
  (pacman -S "--noconfirm" "--noprogressbar"
   ~(stevedore/option-args options) ~package))

(script/defimpl remove-package [#{:pacman}] [package & options]
  (pacman -R "--noconfirm" ~(stevedore/option-args options) ~package))

(script/defimpl purge-package [#{:pacman}] [package & options]
  (pacman -R "--noconfirm" "--nosave"
   ~(stevedore/option-args options) ~package))

;; brew
(script/defimpl update-package-list [#{:brew}] [& options]
  (brew update ~(stevedore/option-args options)))

(script/defimpl upgrade-all-packages [#{:brew}] [& options]
  (brew upgrade ~(stevedore/option-args options)))

(script/defimpl install-package [#{:brew}] [package & options]
  (chain-or
   ;; brew install complains if already installed
   (brew ls ~package > "/dev/null" "2>&1")
   (brew install -y ~(stevedore/option-args options) ~package)))

(script/defimpl remove-package [#{:brew}] [package & options]
  (brew uninstall ~(stevedore/option-args options) ~package))

(script/defimpl purge-package [#{:brew}] [package & options]
  (brew uninstall ~(stevedore/option-args options) ~package))


(script/defscript debconf-set-selections [& selections])
(script/defimpl debconf-set-selections :default [& selections] "")
(script/defimpl debconf-set-selections [#{:aptitude}] [& selections]
  ("{ debconf-set-selections"
   ~(str "<<EOF\n" (string/join \newline selections) "\nEOF\n}")))

(script/defscript package-manager-non-interactive [])
(script/defimpl package-manager-non-interactive :default [] "")
(script/defimpl package-manager-non-interactive [#{:aptitude}] []
  (~debconf-set-selections
   "debconf debconf/frontend select noninteractive"
   "debconf debconf/frontend seen false"))


;;; Service functions

(script/defscript configure-service
  [name action options])

(def debian-configure-option-names
     {:force :f})

(defn debian-options [options]
  (zipmap
   (map #(% debian-configure-option-names %) (keys options))
   (vals options)))

(script/defimpl configure-service :default [name action options]
  ~(condp = action
       :disable (stevedore/script
                 ("update-rc.d"
                  ~(stevedore/map-to-arg-string
                    (select-keys [:f :n] (debian-options options)))
                  ~name remove))
       :enable (stevedore/script
                ("update-rc.d"
                 ~(stevedore/map-to-arg-string
                   (select-keys [:n] (debian-options options)))
                 ~name defaults
                 ~(:sequence-start options 20)
                 ~(:sequence-stop options (:sequence-start options 20))))
       :start-stop (stevedore/script ;; start/stop
                    ("update-rc.d"
                     ~(stevedore/map-to-arg-string
                       (select-keys [:n] (debian-options options)))
                     ~name
                     start ~(:sequence-start options 20)
                     "."
                     stop ~(:sequence-stop options (:sequence-start options 20))
                     "."))))

(def ^{:private true} chkconfig-default-options
  [20 2 3 4 5])

(defn- chkconfig-levels
  [options]
  (->> options (drop 1 ) (map str) string/join))

(script/defimpl configure-service [#{:yum}] [name action options]
  ~(condp = action
       :disable (stevedore/script ("/sbin/chkconfig" ~name off))
       :enable (stevedore/script
                ("/sbin/chkconfig"
                 ~name on
                 "--level" ~(chkconfig-levels
                             (:sequence-start
                              options chkconfig-default-options))))
       :start-stop (stevedore/script ;; start/stop
                    ("/sbin/chkconfig"
                     ~name on
                     "--level" ~(chkconfig-levels
                                 (:sequence-start
                                  options chkconfig-default-options))))))




;;; Functions to return distribution specific paths.
;;;
;;; These script functions are meant to help build distribution agnostic crates.
;;;  * Links
;;;   - man 7 hier
;;;   - http://www.pathname.com/fhs/
;;;   - http://wiki.apache.org/httpd/DistrosDefaultLayout
;;;

(script/defscript etc-default [])
(script/defimpl etc-default [#{:ubuntu :debian :jeos}] []
  "/etc/default")
(script/defimpl etc-default [#{:centos :rhel :amzn-linux :fedora}] []
  "/etc/sysconfig")
(script/defimpl etc-default [#{:os-x :darwin}] []
  "/etc/defaults")

(script/defscript log-root [])
(script/defimpl log-root :default []
  "/var/log")

(script/defscript pid-root [])
(script/defimpl pid-root :default []
  "/var/run")

(script/defscript config-root [])
(script/defimpl config-root :default []
  "/etc")

(script/defscript etc-hosts [])
(script/defimpl etc-hosts :default []
  "/etc/hosts")

(script/defscript etc-init [])
(script/defimpl etc-init :default [] "/etc/init.d")
(script/defimpl etc-init [:pacman] [] "/etc/rc.d")

(script/defscript upstart-script-dir [])
(script/defimpl upstart-script-dir :default [] "/etc/init")

;; Some of the packagers, like brew, are "add-ons" in the sense that they are
;; outside of the base system.  These paths refer to locations of packager
;; installed files.

(script/defscript pkg-etc-default [])
(script/defimpl pkg-etc-default :default [] (~etc-default))
(script/defimpl etc-default [:brew] [] "/usr/local/etc/default")

(script/defscript pkg-log-root [])
(script/defimpl pkg-log-root :default [] (~log-root))
(script/defimpl pkg-log-root [:brew] [] "/usr/local/var/log")

(script/defscript pkg-pid-root [])
(script/defimpl pkg-pid-root :default [] (~pid-root))
(script/defimpl pkg-pid-root [:brew] [] "/usr/local/var/run")

(script/defscript pkg-config-root [])
(script/defimpl pkg-config-root :default [] (~config-root))
(script/defimpl pkg-config-root [:brew] [] "/usr/local/etc")

(script/defscript pkg-sbin [])
(script/defimpl pkg-sbin :default [] "/sbin")
(script/defimpl pkg-sbin [:brew] [] "/usr/local/sbin")


;;; #Flags#
;;; Flags are used to communicate state from the node to the origin

;;; Register changed files

(script/defscript file-changed [path])
(script/defimpl file-changed :default [path]
  (assoc! changed_files path 1))

;; (script/defscript set-flag [path])
;; (script/defimpl set-flag :default [path]
;;   (assoc! flags_hash ~(name path) 1))

(script/defscript set-flag [flag-name])
(script/defimpl set-flag :default [flag-name]
  (println "SETFLAG:" ~flag-name ":SETFLAG"))

(script/defscript set-flag-value [flag-name flag-value])
(script/defimpl set-flag-value :default [flag-name flag-value]
  (println "SETVALUE:" ~flag-name ~flag-value ":SETVALUE"))

(script/defscript flag? [path])
(script/defimpl flag? :default [path]
  (get flags_hash ~(name path)))

;;; selinux

(script/defscript selinux-file-type
  "Set the selinux file type"
  [path type])

(script/defimpl selinux-file-type :default
  [path type]
  (if (&& (~has-command? chcon) (directory? "/etc/selinux"))
    (chcon -Rv ~(str "--type=" type) ~path)))

(script/defscript selinux-bool
  "Set the selinux boolean value"
  [flag value & {:keys [persist]}])

(script/defimpl selinux-bool :default
  [flag value & {:keys [persist]}]
  (if (&& (&& (~has-command? setsebool) (directory? "/etc/selinux"))
          (file-exists? "/selinux/enforce"))
    (setsebool ~(if persist "-P" "") ~(name flag) ~value)))
