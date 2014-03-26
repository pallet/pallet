(ns pallet.actions.direct.package
  "Package management action.

   `package` is used to install or remove a package.

   `package-source` is used to specify a non-standard source for packages."
  (:require
   [clojure.string :as string]
   [taoensso.timbre :as logging]
   [pallet.action :refer [implement-action]]
   [pallet.actions :refer [package-source-changed-flag]]
   [pallet.actions.decl :refer [add-rpm
                                debconf-set-selections
                                install-deb
                                package
                                packages
                                package-manager
                                package-repository
                                package-source]]
   [pallet.actions.impl :refer [checked-commands]]
   [pallet.actions.direct.file :refer [sed*]]
   [pallet.actions.direct.remote-file :refer [remote-file*]]
   [pallet.script.lib :as lib]
   [pallet.target :refer [os-family]]
   [pallet.script :refer [with-script-context *script-context*]]
   [pallet.stevedore :as stevedore
    :refer [checked-script fragment with-source-line-comments]]
   [pallet.utils :refer [apply-map]]
   [pallet.version-dispatch :refer [os-map os-map-lookup]]))

(defmulti adjust-packages
  (fn [packager & _]
    packager))

;; http://algebraicthunk.net/~dburrows/projects/aptitude/doc/en/ch02s03s01.html
(def ^{:private true} aptitude-escape-map
  {\+ "\\+"
   \- "\\-"
   \. "\\."
   \( "\\"
   \) "\\)"
   \| "\\|"
   \[ "\\["
   \] "\\]"
   \^ "\\^"
   \$ "\\$"})

;; aptitude and apt can install, remove and purge all in one command, so we just
;; need to split by enable/disable options.
(defmethod adjust-packages :aptitude
  [_ packages]
  (logging/tracef "adjust-packages :aptitude %s" (vec packages))
  (checked-commands
   "Packages"
   (stevedore/script (~lib/package-manager-non-interactive))
   (stevedore/script (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d")))
   (stevedore/chain-commands*
    (for [[opts packages] (->>
                           packages
                           (group-by #(select-keys % [:enable :allow-unsigned
                                                      :disable-service-start]))
                           (sort-by #(apply min (map :priority (second %)))))]
      (stevedore/chained-script
       ~(if (:disable-service-start opts)
          (do
            (stevedore/script
             (chain-and
              ("trap" enableStart EXIT)
              (lib/heredoc "/usr/sbin/policy-rc.d" "#!/bin/sh\nexit 101" {}))))
          "")
       ("aptitude"
        install -q -y
        ~(string/join " " (map #(str "-t " %) (:enable opts)))
        ~(if (:allow-unsigned opts)
           "-o 'APT::Get::AllowUnauthenticated=true'"
           "")
        ~(string/join
          " "
          (for [[action packages] (group-by :action packages)
                {:keys [package force purge]} packages]
            (case action
              :install (format "%s+" package)
              :remove (if purge
                        (format "%s_" package)
                        (format "%s-" package))
              :upgrade (format "%s+" package)
              (throw
               (IllegalArgumentException.
                (str
                 action " is not a valid action for package action")))))))
       ~(if (:disable-service-start opts)
          (do
            (stevedore/script
             (chain-and
              ("enableStart")
              ("trap" - EXIT))))
          ""))))
   ;; aptitude doesn't report failed installed in its exit code
   ;; so explicitly check for success
   (stevedore/chain-commands*
    (for [{:keys [package action]} packages
          :let [escaped-package (string/escape package aptitude-escape-map)]]
      (cond
       (#{:install :upgrade} action)
       (stevedore/script
        (pipe ("aptitude"
               search
               --disable-columns
               (quoted
                (str "?and(?installed, ?name(^" ~escaped-package "$))")))
              ("grep" (quoted ~package))))
       (= :remove action)
       (stevedore/script
        (not (pipe ("aptitude"
                    search
                    --disable-columns
                    (quoted
                     (str "?and(?installed, ?name(^" ~escaped-package "$))")))
                   ("grep" (quoted ~package))))))))))

(defmethod adjust-packages :apt
  [_ packages]
  (checked-commands
   "Packages"
   (stevedore/script (~lib/package-manager-non-interactive))
   (stevedore/script (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d")))
   (stevedore/chain-commands*
    (for [[opts packages] (->>
                           packages
                           (group-by #(select-keys % [:enable :allow-unsigned
                                                      :disable-service-start]))
                           (sort-by #(apply min (map :priority (second %)))))]
      (stevedore/chained-script
       ~(if (:disable-service-start opts)
          (do
            (stevedore/script
             (chain-and
              ("trap" enableStart EXIT)
              (lib/heredoc "/usr/sbin/policy-rc.d" "#!/bin/sh\nexit 101" {}))))
          "")
       ("apt-get"
        -q -y install
        ~(string/join " " (map #(str "-t " %) (:enable opts)))
        ~(if (:allow-unsigned opts) "--allow-unauthenticated" "")
        ~(string/join
          " "
          (for [[action packages] (group-by :action packages)
                {:keys [package force purge]} packages]
            (case action
              :install (format "%s+" package)
              :remove (if purge
                        (format "%s_" package)
                        (format "%s-" package))
              :upgrade (format "%s+" package)
              (throw
               (IllegalArgumentException.
                (str
                 action " is not a valid action for package action")))))))
       ~(if (:disable-service-start opts)
          (do
            (stevedore/script
             (chain-and
              ("enableStart")
              ("trap" - EXIT))))
          ""))))))

(def ^{:private true :doc "Define the order of actions"}
  action-order {:install 10 :remove 20 :upgrade 30})

;; `yum` has separate install, remove and purge commands, so we just need to
;; split by enable/disable options and by command.  We install before removing.
(defmethod adjust-packages :yum
  [_ packages]
  (checked-commands
   "Packages"
   (stevedore/chain-commands*
    (conj
     (vec
      (for [[action packages] (->> packages
                                   (sort-by #(action-order (:action %)))
                                   (group-by :action))
            [opts packages] (->>
                             packages
                             (group-by
                              #(select-keys % [:enable :disable :exclude]))
                             (sort-by #(apply min (map :priority (second %)))))]
        (stevedore/script
         ("yum"
          ~(name action) -q -y
          ~(string/join " " (map #(str "--disablerepo=" %) (:disable opts)))
          ~(string/join " " (map #(str "--enablerepo=" %) (:enable opts)))
          ~(string/join " " (map #(str "--exclude=" %) (:exclude opts)))
          ~(string/join
            " "
            (distinct (map :package packages)))))))
     (stevedore/script (~lib/list-installed-packages))))))


(defmethod adjust-packages :default
  [_ packages]
  (checked-commands
   "Packages"
   (stevedore/chain-commands*
    (list*
     (stevedore/script (~lib/package-manager-non-interactive))
     (for [[action packages] (group-by :action packages)
           {:keys [package force purge]} packages]
       (case action
         :install (stevedore/script
                   (~lib/install-package ~package :force ~force))
         :remove (if purge
                   (stevedore/script (~lib/purge-package ~package))
                   (stevedore/script (~lib/remove-package ~package)))
         :upgrade (stevedore/script (~lib/upgrade-package ~package))
         (throw
          (IllegalArgumentException.
           (str action " is not a valid action for package action")))))))))

(defn- package-map
  "Convert the args into a single map"
  [package-name
   {:keys [action y force purge priority enable disable allow-unsigned]
    :as options}]
  (logging/tracef "package-map %s %s" package-name options)
  (letfn [(as-seq [x] (if (or (string? x) (symbol? x) (keyword? x))
                        [(name x)] x))]
    (->
     {:action :install :y true :priority 50}
     (merge options)
     (assoc :package package-name)
     (update-in [:enable] as-seq)
     (update-in [:disable] as-seq))))

(defn package*
  [action-state package-name options]
  (logging/tracef "package %s" package-name)
  [{:language :bash
    :summary (str "package " package-name)}
   (with-script-context (conj *script-context* (:packager options))
     (adjust-packages
      (:packager options)
      [(package-map package-name (dissoc options :packager))]))])

(implement-action package :direct {} package*)

(defn packages*
  [action-state package-names options]
  (logging/tracef "packages %s" package-names)
  [{:language :bash
    :summary (str "packages " (string/join " " package-names))}
   (with-script-context (conj *script-context* (:packager options))
     (adjust-packages
      (:packager options)
      (map #(package-map % (dissoc options :packager)) package-names)))])

(implement-action packages :direct {} packages*)

(def source-location
  {:aptitude "/etc/apt/sources.list.d/%s.list"
   :apt "/etc/apt/sources.list.d/%s.list"
   :yum "/etc/yum.repos.d/%s.repo"})

(defmulti format-source
  "Format a package source definition"
  (fn [packager & _] packager))

(defmethod format-source :aptitude
  [_ name options]
  (format
   "%s %s %s %s\n"
   (:source-type options "deb")
   (:url options)
   (:release options (with-source-line-comments false
                       (stevedore/script (~lib/os-version-name))))
   (string/join " " (:scopes options ["main"]))))

(defmethod format-source :apt
  [_ name options]
  (format-source :aptitude name options))

(defmethod format-source :yum
  [_ name {:keys [url mirrorlist gpgcheck gpgkey priority failovermethod
                  enabled]
           :or {enabled 1}
           :as options}]
  (string/join
   "\n"
   (filter
    identity
    [(format "[%s]\nname=%s" name name)
     (when url (format "baseurl=%s" url))
     (when mirrorlist (format "mirrorlist=%s" mirrorlist))
     (format "gpgcheck=%s" (or (and gpgkey 1) 0))
     (when gpgkey (format "gpgkey=%s" gpgkey))
     (when priority (format "priority=%s" priority))
     (when failovermethod (format "failovermethod=%s" failovermethod))
     (format "enabled=%s" enabled)
     ""])))

(def ^{:dynamic true} *default-apt-keyserver* "subkeys.pgp.net")

(defmulti package-source*
  "Add a packager source."
  (fn [action-state name {:keys [packager] :as options}]
    packager))

(def ubuntu-ppa-add
  (atom                                 ; allow for open extension
   (os-map
    {{:os :ubuntu :os-version [[10] [12 04]]} "python-software-properties"
     {:os :ubuntu :os-version [[12 10] nil]} "software-properties-common"})))

(defn- package-source-apt
  [name {:keys [key-id key-server key-url url] :as options}]
  (checked-commands
   "Package source"
   (let [^String key-url url]
     (if (and key-url (.startsWith key-url "ppa:"))
       (let [list-file (str
                        (string/replace (subs key-url 4) "/" "-")
                        "-"
                        (fragment (lib/os-version-name))
                        ".list")]
         (stevedore/chain-commands
          (if-let [package (os-map-lookup @ubuntu-ppa-add)]
            (stevedore/script
             (chain-and
              ("apt-cache" show ~package ">" "/dev/null") ; fail if unavailable
              (~lib/install-package ~package))))
          (stevedore/script
           (when (not (file-exists? (lib/file "/etc/apt/sources.list.d"
                                              ~list-file)))
             (chain-and
              (pipe (println "") ("add-apt-repository" ~key-url))
              (~lib/update-package-list))))))
       (->
        (remote-file*
         {}
         (format (source-location :apt) name)
         {:content (format-source :apt name options)
          :flag-on-changed package-source-changed-flag})
        second)))
   (if key-id
     (let [key-server (or key-server *default-apt-keyserver*)]
       (stevedore/script
        ("apt-key" adv "--keyserver" ~key-server "--recv-keys" ~key-id))))
   (if key-url
     (stevedore/chain-commands
      (->
       (remote-file*
        {}
        "aptkey.tmp"
        {:url key-url :flag-on-changed package-source-changed-flag})
       second)
      (stevedore/script ("apt-key" add aptkey.tmp))))))

(defmethod package-source* :aptitude
  [action-state name {:keys [apt aptitude yum] :as options}]
  (package-source-apt name options))

(defmethod package-source* :apt
  [action-state name {:keys [apt aptitude yum] :as options}]
  (package-source-apt name options))

(defmethod package-source* :yum
  [action-state name {:keys [packager gpgkey] :as options}]
  (checked-commands
   "Package source"
   (->
    (remote-file*
     {}
     (format (source-location packager) name)
     {:content (format-source packager name options)
      :literal true
      :flag-on-changed package-source-changed-flag})
    second)
   (if gpgkey
     (stevedore/script ("rpm" "--import" ~gpgkey)))))

(defmethod package-source* :default
  [action-state name {:keys [packager] :as options}]
  (checked-commands
   "Package source"
   (->
    (remote-file*
     {}
     (format (source-location packager) name)
     {:content (format-source packager name options)
      :flag-on-changed package-source-changed-flag})
    first second)))

(implement-action package-source :direct {} {:language :bash} package-source*)

(defn package-repository*
  [action-options options]
  [{:language :bash
    :summary (str "package-repository " (:repository-name options))}
   (package-source* (:repository-name options) options)])

(implement-action package-repository :direct {} package-repository*)

(defn add-scope*
  "Add a scope to all the existing package sources. Aptitude specific."
  [type scope file]
  (stevedore/chained-script
   (var tmpfile @("mktemp" -t addscopeXXXX))
   (~lib/cp ~file @tmpfile :preserve true)
   ("awk" "'{if ($1 ~" ~(str "/^" type "/") "&& !" ~(str "/" scope "/")
        " ) print $0 \" \" \"" ~scope  "\" ; else print; }'"
        ~file > @tmpfile)
   (~lib/mv @tmpfile ~file :force ~true)))

(defn add-scope
  "Add a scope to an apt source"
  [opts]
  (add-scope*
   (or (opts :type) "deb.*")
   (:scope opts)
   (or (opts :file) "/etc/apt/sources.list")))

(defmulti configure-package-manager
  "Configure the package manager"
  (fn [packager options] packager))

(defmulti package-manager-option
  "Provide packager specific options"
  (fn [packager option value] [packager option]))

(defmethod package-manager-option [:aptitude :proxy]
  [packager proxy proxy-url]
  (format "ACQUIRE::http::proxy \"%s\";" proxy-url))

(defmethod package-manager-option [:apt :proxy]
  [packager proxy proxy-url]
  (package-manager-option :aptitude proxy proxy-url))

(defmethod package-manager-option [:yum :proxy]
  [packager proxy proxy-url]
  (format "proxy=%s" proxy-url))

(defmethod package-manager-option [:pacman :proxy]
  [packager proxy proxy-url]
  (format
   (str "XferCommand = /usr/bin/wget "
        "-e \"http_proxy = %s\" -e \"ftp_proxy = %s\" "
        "--passive-ftp --no-verbose -c -O %%o %%u")
   proxy-url proxy-url))

(def default-installonlypkgs
  (str "kernel kernel-smp kernel-bigmem kernel-enterprise kernel-debug "
       "kernel-unsupported"))

(defmethod package-manager-option [:yum :installonlypkgs]
  [packager installonly packages]
  (format
   "installonlypkgs=%s %s" (string/join " " packages) default-installonlypkgs))

(defmethod configure-package-manager :aptitude
  [packager {:keys [priority prox] :or {priority 50} :as options}]
  (->
   (remote-file*
    {}
    (format "/etc/apt/apt.conf.d/%spallet" priority)
    {:content (string/join
               \newline
               (map
                #(package-manager-option packager (key %) (val %))
                (dissoc options :priority :packager)))
     :literal true})
   second))

(defmethod configure-package-manager :apt
  [packager {:as options}]
  (configure-package-manager :aptitude options))

(defmethod configure-package-manager :yum
  [packager {:keys [proxy] :as options}]
  (stevedore/chain-commands
   (->
    (remote-file*
     {}
     "/etc/yum.pallet.conf"
     {:content (string/join
                \newline
                (map
                 #(package-manager-option packager (key %) (val %))
                 (dissoc options :priority :packager)))
      :literal true})
    second)
   ;; include yum.pallet.conf from yum.conf
   (stevedore/script
    (if (not @("fgrep" "yum.pallet.conf" "/etc/yum.conf"))
      (do
        ("cat" ">>" "/etc/yum.conf" " <<'EOFpallet'")
        "include=file:///etc/yum.pallet.conf"
        "EOFpallet")))))

(defmethod configure-package-manager :pacman
  [packager {:keys [proxy] :as options}]
  (stevedore/chain-commands
   (->
    (remote-file*
     {}
     "/etc/pacman.pallet.conf"
     {:content (string/join
                \newline
                (map
                 #(package-manager-option packager (key %) (val %))
                 (dissoc options :priority :packager)))
      :literal true})
    second)
   ;; include pacman.pallet.conf from pacman.conf
   (stevedore/script
    (if (not @("fgrep" "pacman.pallet.conf" "/etc/pacman.conf"))
      (do
        ~(-> (sed*
              {}
              "/etc/pacman.conf"
              "a Include = /etc/pacman.pallet.conf"
              :restriction "/\\[options\\]/")
             second))))))

(defmethod configure-package-manager :default
  [packager {:as options}]
  (comment "do nothing"))

(defn package-manager*
  "Package management."
  [action-state action {:keys [packager packages scope] :as options}]
  [{:language :bash
    :summary (str "package-manager " action)}
   (checked-commands
    (format "package-manager %s" (name action))
    (case action
      :update (stevedore/script (apply ~lib/update-package-list ~packages))
      :upgrade (stevedore/script (~lib/upgrade-all-packages))
      :list-installed (stevedore/script (~lib/list-installed-packages))
      :add-scope (add-scope scope)
      :multiverse (add-scope (merge {:scope "multiverse"} options))
      :universe (add-scope (merge {:scope "universe"} options))
      ;; :debconf (if (#{:aptitude :apt} packager)
      ;;            (stevedore/script
      ;;             (apply ~lib/debconf-set-selections ~options)))
      :configure (configure-package-manager packager options)
      (throw (IllegalArgumentException.
              (str action
                   " is not a valid action for package-manager action")))))])

(implement-action package-manager :direct {} package-manager*)

(defn add-rpm*
  [action-options rpm-name & {:as options}]
  (stevedore/do-script
   (->
    (remote-file*
     {}
     rpm-name
     (merge
      {:install-new-files pallet.actions.impl/*install-new-files*
       :overwrite-changes pallet.actions.impl/*force-overwrite*}
      options))
    second)
   (checked-script
    (format "Install rpm %s" rpm-name)
    (if-not ("rpm" -q @("rpm" -pq ~rpm-name) > "/dev/null" "2>&1")
      (do ("rpm" -U --quiet ~rpm-name))))))

(implement-action add-rpm :direct {} {:language :bash} add-rpm*)

(defn install-deb*
  [action-options deb-name & {:as options}]
  (stevedore/do-script
   (-> (remote-file*
        {}
        deb-name
        (merge
         {:install-new-files pallet.actions.impl/*install-new-files*
          :overwrite-changes pallet.actions.impl/*force-overwrite*}
         options))
       second)
   (checked-script
    (format "Install deb %s" deb-name)
    ("dpkg" -i --skip-same-version ~deb-name))))

(implement-action install-deb :direct {} {:language :bash} install-deb*)

(defn debconf-set-selections*
  [action-options {:keys [line package question type value]}]
  {:pre [(or line (and package question type (not (nil? value))))]}
  (stevedore/do-script
   (checked-script
    (format "Preseed %s"
            (or line (string/join " " [package question type value])))
    (pipe
     (println
      (quoted ~@(if line
                  [line]
                  [(name package) question (name type) (pr-str value)])))
     ("/usr/bin/debconf-set-selections")))))

(implement-action
    debconf-set-selections :direct {} {:language :bash} debconf-set-selections*)
