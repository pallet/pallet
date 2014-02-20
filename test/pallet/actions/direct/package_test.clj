(ns pallet.actions.direct.package-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [pallet.action :refer [action-fn]]
   [pallet.actions
    :refer [add-rpm
            debconf-set-selections
            exec-script*
            exec-checked-script
            package
            package-manager
            package-source
            packages
            remote-file
            sed]]
   [pallet.actions.decl :refer [checked-commands remote-file-action]]
   [pallet.actions.direct.package
    :refer [add-scope* adjust-packages package-manager* package-source*]]
   [pallet.api :refer [group-spec]]
   [pallet.build-actions
    :refer [build-actions build-script build-session centos-session
            ubuntu-session]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.plan :refer [plan-context]]
   [pallet.local.execute :as local]
   [pallet.script :as script]
   [pallet.script :refer [with-script-context]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils :as test-utils]))

(use-fixtures
 :each
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language
 test-utils/with-no-source-line-comments
 test-utils/no-location-info)

(use-fixtures
 :once
 (logging-threshold-fixture))

(def remote-file* (action-fn remote-file-action :direct))
(def sed* (action-fn sed :direct))

(deftest test-install-example
  (testing "apt"
    (testing "package"
      (is (script-no-comment=
           (first
            (build-actions
                {}
              (exec-checked-script
               "Packages"
               (~lib/package-manager-non-interactive)
               (chain-and
                (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
                "apt-get -q -y install java+"
                ("dpkg" "--get-selections")))
              (exec-checked-script
               "Packages"
               (~lib/package-manager-non-interactive)
               (chain-and
                (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
                "apt-get -q -y install rubygems+"
                ("dpkg" "--get-selections")))
              (exec-checked-script
               "Packages"
               (~lib/package-manager-non-interactive)
               (chain-and
                (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
                "apt-get -q -y install git-"
                ("dpkg" "--get-selections")))
              (exec-checked-script
               "Packages"
               (~lib/package-manager-non-interactive)
               (chain-and
                (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
                "apt-get -q -y install ruby_"
                ("dpkg" "--get-selections")))))
           (first
            (build-actions
                {}
              (package "java" :action :install)
              (package "rubygems")
              (package "git" :action :remove)
              (package "ruby" :action :remove :purge true)))))))
  (testing "aptitude"
    (testing "package"
      (is (script-no-comment=
           (first
            (build-actions
                {:server {:packager :aptitude :image {:os-family :ubuntu}}}
              (exec-checked-script
               "Packages"
               (~lib/package-manager-non-interactive)
               (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
               "aptitude install -q -y java+"
               "aptitude search \"?and(?installed, ?name(^java$))\" | grep \"java\"")
              (exec-checked-script
               "Packages"
               (~lib/package-manager-non-interactive)
               (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
               "aptitude install -q -y rubygems+"
               "aptitude search \"?and(?installed, ?name(^rubygems$))\" | grep \"rubygems\"")
              (exec-checked-script
               "Packages"
               (~lib/package-manager-non-interactive)
               (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
               "aptitude install -q -y git-"
               "! { aptitude search \"?and(?installed, ?name(^git$))\" | grep \"git\"; }")
              (exec-checked-script
               "Packages"
               (~lib/package-manager-non-interactive)
               (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
               "aptitude install -q -y ruby_"
               "! { aptitude search \"?and(?installed, ?name(^ruby$))\" | grep \"ruby\"; }")))
           (first
            (build-actions
                {:server {:packager :aptitude :image {:os-family :ubuntu}}}
              (package "java" :action :install)
              (package "rubygems")
              (package "git" :action :remove)
              (package "ruby" :action :remove :purge true)))))))
  (testing "yum"
    (testing "package"
      (is (script-no-comment=
           (first
            (build-actions
                {:server {:tag :n :image {:os-family :centos}}}
              (exec-checked-script
               "Packages"
               "yum install -q -y java"
               ("yum" list installed))
              (exec-checked-script
               "Packages"
               "yum install -q -y rubygems"
               ("yum" list installed))
              (exec-checked-script
               "Packages"
               "yum upgrade -q -y maven2"
               ("yum" list installed))
              (exec-checked-script
               "Packages"
               "yum remove -q -y git"
               ("yum" list installed))
              (exec-checked-script
               "Packages"
               "yum remove -q -y ruby"
               ("yum" list installed))))
           (first
            (build-actions
                {:server {:tag :n :image {:os-family :centos}}}
              (package "java" :action :install)
              (package "rubygems")
              (package "maven2" :action :upgrade)
              (package "git" :action :remove)
              (package "ruby" :action :remove :purge true)))))))
  (testing "pacman"
    (is (script-no-comment=
         (first
          (build-actions
              {:server {:tag :n :image {:os-family :arch}}}
            (exec-checked-script
             "Packages"
             "pacman -S --noconfirm --noprogressbar java")
            (exec-checked-script
             "Packages"
             "pacman -S --noconfirm --noprogressbar rubygems")
            (exec-checked-script
             "Packages"
             "pacman -S --noconfirm --noprogressbar maven2")
            (exec-checked-script
             "Packages"
             "pacman -R --noconfirm git")
            (exec-checked-script
             "Packages"
             "pacman -R --noconfirm --nosave ruby")))
         (first
          (build-actions
              {:server {:tag :n :image {:os-family :arch}}}
            (package "java" :action :install)
            (package "rubygems")
            (package "maven2" :action :upgrade)
            (package "git" :action :remove)
            (package "ruby" :action :remove :purge true)))))))

(deftest package-manager-non-interactive-test
  (is (script-no-comment=
       "{ debconf-set-selections <<EOF
debconf debconf/frontend select noninteractive
debconf debconf/frontend seen false
EOF
}"
       (script/with-script-context [:aptitude]
         (stevedore/script (~lib/package-manager-non-interactive))))))

(deftest add-scope-test
  (is (script-no-comment=
       (stevedore/chained-script
        (set! tmpfile @("mktemp" -t addscopeXXXX))
        ("cp" -p "/etc/apt/sources.list" @tmpfile)
        ("awk" "'{if ($1 ~ /^deb/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'" "/etc/apt/sources.list" > @tmpfile)
        ("mv" -f @tmpfile "/etc/apt/sources.list"))
       (add-scope* "deb" "multiverse" "/etc/apt/sources.list")))

  (testing "with sources.list"
    (let [tmp (java.io.File/createTempFile "package_test" "test")]
      (io/copy "deb http://archive.ubuntu.com/ubuntu/ karmic main restricted
deb-src http://archive.ubuntu.com/ubuntu/ karmic main restricted"
               tmp)
      (is (=
           {:exit 0, :out "", :err ""}
           (local/local-script
            ~(add-scope* "deb" "multiverse" (.getPath tmp)))))
      (is
       (=
        (str "deb http://archive.ubuntu.com/ubuntu/ karmic main restricted  "
             "multiverse \ndeb-src http://archive.ubuntu.com/ubuntu/ karmic "
             "main restricted  multiverse \n")
        (slurp (.getPath tmp))))
      (.delete tmp))))

(deftest package-manager*-test
  (is (script-no-comment=
       (build-script {}
         (exec-checked-script
          "package-manager multiverse "
          (set! tmpfile @("mktemp" -t addscopeXXXX))
          ("cp" -p "/etc/apt/sources.list" @tmpfile)
          ("awk" "'{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'" "/etc/apt/sources.list" > @tmpfile)
          ("mv" -f @tmpfile "/etc/apt/sources.list")))
       (build-script {}
         (package-manager :multiverse))))
  (is (script-no-comment=
       (build-script {}
         (exec-checked-script
          "package-manager update "
          (chain-or
           ("aptitude" update "-q=2" -y)
           true)))
       (build-script {:group {:packager :aptitude}}
         (package-manager :update)))))

(deftest package-manager-update-test
  (testing "yum"
    (is (script-no-comment=
         (first
          (build-actions
              {:server {:group-name :n :image {:os-family :centos}}}
            (exec-checked-script
             "package-manager update :enable [\"r1\"]"
             ("yum" makecache -q "--enablerepo=r1"))))
         (first
          (build-actions
              {:server {:group-name :n :image {:os-family :centos}}}
            (package-manager :update :enable ["r1"])))))))

(deftest package-manager-configure-test
  (testing "aptitude"
    (is (script-no-comment=
         (first
          (build-actions {}
            (exec-checked-script
             "package-manager configure :proxy http://192.168.2.37:3182"
             ~(->
               (remote-file*
                "/etc/apt/apt.conf.d/50pallet"
                {:content "ACQUIRE::http::proxy \"http://192.168.2.37:3182\";"
                 :literal true})
               second))))
         (first
          (build-actions {}
            (package-manager
             :configure :proxy "http://192.168.2.37:3182"))))))
  (testing "yum"
    (is (script-no-comment=
         (first
          (build-actions
              {:server {:tag :n :image {:os-family :centos}}}
            (exec-checked-script
             "package-manager configure :proxy http://192.168.2.37:3182"
             ~(->
               (remote-file*
                "/etc/yum.pallet.conf"
                {:content "proxy=http://192.168.2.37:3182"
                 :literal true})
               second)
             (if (not @("fgrep" "yum.pallet.conf" "/etc/yum.conf"))
               (do
                 ("cat" ">>" "/etc/yum.conf" " <<'EOFpallet'")
                 "include=file:///etc/yum.pallet.conf"
                 "EOFpallet")))))
         (first
          (build-actions
              {:server {:tag :n :image {:os-family :centos}}}
            (package-manager
             :configure :proxy "http://192.168.2.37:3182"))))))
  (testing "pacman"
    (is (script-no-comment=
         (first
          (build-actions
              {:server {:tag :n :image {:os-family :arch}}}
            (exec-checked-script
             "package-manager configure :proxy http://192.168.2.37:3182"
             ~(->
               (remote-file*
                "/etc/pacman.pallet.conf"
                {:content (str "XferCommand = /usr/bin/wget "
                               "-e \"http_proxy = http://192.168.2.37:3182\" "
                               "-e \"ftp_proxy = http://192.168.2.37:3182\" "
                               "--passive-ftp --no-verbose -c -O %o %u")
                 :literal true})
               second)
             (if (not @("fgrep" "pacman.pallet.conf" "/etc/pacman.conf"))
               (do
                 ~(->
                   (sed*
                    "/etc/pacman.conf"
                    "a Include = /etc/pacman.pallet.conf"
                    :restriction "/\\[options\\]/")
                    second))))))
         (first
          (build-actions
              {:server {:tag :n :image {:os-family :arch}}}
            (package-manager
             :configure :proxy "http://192.168.2.37:3182")))))))

(deftest add-multiverse-example-test
  (testing "apt"
    (is (script-no-comment=
         (str
          (stevedore/checked-script
           "package-manager multiverse "
           (set! tmpfile @("mktemp" -t addscopeXXXX))
           (~lib/cp "/etc/apt/sources.list" @tmpfile :preserve true)
           ("awk" "'{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'" "/etc/apt/sources.list" > @tmpfile)
           (~lib/mv @tmpfile "/etc/apt/sources.list" :force true))
          (stevedore/checked-script
           "package-manager update "
           ("apt-get" "-qq" update)))
         (first (build-actions
                    {}
                  (package-manager :multiverse)
                  (package-manager :update))))))
  (testing "aptitude"
    (is (script-no-comment=
         (str
          (stevedore/checked-script
           "package-manager multiverse "
           (set! tmpfile @("mktemp" -t addscopeXXXX))
           (~lib/cp "/etc/apt/sources.list" @tmpfile :preserve true)
           ("awk" "'{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'" "/etc/apt/sources.list" > @tmpfile)
           (~lib/mv @tmpfile "/etc/apt/sources.list" :force true))
          (stevedore/checked-script
           "package-manager update "
           (chain-or
            ("aptitude" update "-q=2" -y "")
            true)))
         (first (build-actions
                    {:server {:packager :aptitude :image {:os-family :ubuntu}}}
                  (package-manager :multiverse)
                  (package-manager :update)))))))

(deftest package-source-test
  (let [a (group-spec "a" :packager :aptitude)
        b (group-spec "b" :packager :yum :image {:os-family :centos})]
    (is (script-no-comment=
         (build-script {}
           (exec-script*
            (checked-commands
             "Package source"
             (->
              (remote-file*
               "/etc/apt/sources.list.d/source1.list"
               {:content "deb http://somewhere/apt $(lsb_release -c -s) main\n"
                :flag-on-changed "packagesourcechanged"})
              second))))
         (build-script {}
           (package-source
            "source1"
            :aptitude {:url "http://somewhere/apt" :scopes ["main"]}
            :yum {:url "http://somewhere/yum"}))))
    (is
     (script-no-comment=

      (stevedore/checked-commands
       "Package source"
       (->
        (remote-file*
         ;; centos-session
         "/etc/yum.repos.d/source1.repo"
         {:content
          "[source1]\nname=source1\nbaseurl=http://somewhere/yum\ngpgcheck=0\nenabled=1\n"
          :literal true
          :flag-on-changed "packagesourcechanged"})
        second))
      (package-source*
       ;; centos-session
       "source1"
       :aptitude {:url "http://somewhere/apt"
                  :scopes ["main"]}
       :yum {:url "http://somewhere/yum"})))
    (testing "ppa pre 12.10"
      (is (script-no-comment=
           (first
            (build-actions
                {}
              (exec-checked-script
               "Package source"
               ("apt-cache" show "python-software-properties" ">" "/dev/null")
               (~lib/install-package "python-software-properties")
               (when
                   (not
                    (file-exists?
                     "/etc/apt/sources.list.d/abc-$(lsb_release -c -s).list"))
                 (chain-and
                  (pipe (println) ("add-apt-repository" "ppa:abc"))
                  (~lib/update-package-list))))))
           (first
            (build-actions
                {:server {:image {:os-family :ubuntu :os-version "12.04"}}}
              (package-source
               "source1"
               :aptitude {:url "ppa:abc"}
               :yum {:url "http://somewhere/yum"}))))))
    (testing "ppa for 12.10"
      (is (script-no-comment=
           (first
            (build-actions
                {}
              (exec-checked-script
               "Package source"
               ("apt-cache" show "software-properties-common" ">" "/dev/null")
               (~lib/install-package "software-properties-common")
               (when
                   (not
                    (file-exists?
                     "/etc/apt/sources.list.d/abc-$(lsb_release -c -s).list"))
                 (chain-and
                  (pipe (println) ("add-apt-repository" "ppa:abc"))
                  (~lib/update-package-list))))))
           (first
            (build-actions
                {:server {:image {:os-family :ubuntu :os-version "12.10"}}}
              (package-source
               "source1"
               :aptitude {:url "ppa:abc"}
               :yum {:url "http://somewhere/yum"}))))))
    (is (script-no-comment=
         (stevedore/checked-commands
          "Package source"
          (->
           (remote-file*
            ;; ubuntu-session
            "/etc/apt/sources.list.d/source1.list"
            {:content "deb http://somewhere/apt $(lsb_release -c -s) main\n"
             :flag-on-changed "packagesourcechanged"})
           second)
          (stevedore/script
           ("apt-key" adv "--keyserver" subkeys.pgp.net "--recv-keys" 1234)))
         (package-source*
          ;; ubuntu-session
          "source1"
          :aptitude {:url "http://somewhere/apt"
                     :scopes ["main"]
                     :key-id 1234}
          :yum {:url "http://somewhere/yum"})))
    (testing "key-server"
      (is (script-no-comment=
           (stevedore/checked-commands
            "Package source"
            (->
             (remote-file*
              ;; ubuntu-session
              "/etc/apt/sources.list.d/source1.list"
              {:content "deb http://somewhere/apt $(lsb_release -c -s) main\n"
               :flag-on-changed "packagesourcechanged"})
             second)
            (stevedore/script
             ("apt-key" adv "--keyserver" keys.ubuntu.com "--recv-keys" 1234)))
           (package-source*
            ;; ubuntu-session
            "source1"
            :aptitude {:url "http://somewhere/apt"
                       :scopes ["main"]
                       :key-server "keys.ubuntu.com"
                       :key-id 1234}
            :yum {:url "http://somewhere/yum"}))))))

(deftest package-source-test
  (let [a (group-spec "a" :packager :aptitude)
        b (group-spec "b" :packager :yum :image {:os-family :centos})]
    (is (script-no-comment=
         (build-script {}
           (exec-checked-script
            "Package source"
            ~(->
              (remote-file*
               ;; (build-session {:server a})
               "/etc/apt/sources.list.d/source1.list"
               {:content "deb http://somewhere/apt $(lsb_release -c -s) main\n"
                :flag-on-changed "packagesourcechanged"})
              second)))
         (build-script {:server a}
           (package-source
            "source1"
            :aptitude {:url "http://somewhere/apt"
                       :scopes ["main"]}
            :yum {:url "http://somewhere/yum"}))))
    (is (script-no-comment=
         (build-script centos-session
           (exec-checked-script
            "Package source"
            ~(->
              (remote-file*
               ;; centos-session
               "/etc/yum.repos.d/source1.repo"
               {:content
                "[source1]\nname=source1\nbaseurl=http://somewhere/yum\ngpgcheck=0\nenabled=1\n"
                :flag-on-changed "packagesourcechanged"
                :literal true})
              second)))
         (build-script centos-session
           (package-source
            "source1"
            :aptitude {:url "http://somewhere/apt"
                       :scopes ["main"]}
            :yum {:url "http://somewhere/yum"}))))))

(deftest packages-test
  (let [a (group-spec "a" :packager :aptitude)
        b (group-spec "b" :packager :yum)]
    (is (script-no-comment=
         (first
          (build-actions {}
            (plan-context packages {}
              (package "git-apt")
              (package "git-apt2"))))
         (first (build-actions {}
                  (packages
                   :aptitude ["git-apt" "git-apt2"]
                   :yum ["git-yum"])))))
    (is (script-no-comment=
         (first
          (build-actions centos-session
            (plan-context packages {}
              (package "git-yum"))))
         (first (build-actions centos-session
                  (packages
                   :aptitude ["git-apt"]
                   :yum ["git-yum"])))))))

(deftest adjust-packages-test
  (testing "apt"
    (script/with-script-context [:apt]
      (is (script-no-comment=
           (build-script {}
             (exec-script*
              (stevedore/checked-script
               "Packages"
               (~lib/package-manager-non-interactive)
               (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
               (chain-and
                ("apt-get" -q -y install p1- p4_ p2+ p3+)
                ("dpkg" "--get-selections")))))
           (build-script {}
             (exec-script*
              (adjust-packages
               [{:package "p1" :action :remove}
                {:package "p2" :action :install}
                {:package "p3" :action :upgrade}
                {:package "p4" :action :remove :purge true}])))))))
  (testing "apt with disabled package start"
    (script/with-script-context [:apt]
      (is (script-no-comment=
           (build-script {}
             (exec-script*
              (stevedore/checked-script
               "Packages"
               (~lib/package-manager-non-interactive)
               (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
               (chain-and
                ("trap" enableStart EXIT)
                ("{" ("cat" > "/usr/sbin/policy-rc.d"
                      "<<EOFpallet\n#!/bin/sh\nexit 101\nEOFpallet\n") "}")
                ("apt-get" -q -y install p1- p4_ p2+ p3+)
                ("enableStart")
                ("trap" - EXIT)
                ("dpkg" "--get-selections")))))
           (build-script {}
             (exec-script*
              (adjust-packages
               [{:package "p1" :action :remove :disable-service-start true}
                {:package "p2" :action :install :disable-service-start true}
                {:package "p3" :action :upgrade :disable-service-start true}
                {:package "p4" :action :remove :purge true
                 :disable-service-start true}])))))))
  (testing "aptitude"
    (script/with-script-context [:aptitude]
      (is (script-no-comment=
           (build-script {}
             (exec-checked-script
              "Packages"
              (~lib/package-manager-non-interactive)
              (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
              ("aptitude" install -q -y p1- p4_ p2+ p3+)
              (not (pipe
                    ("aptitude" search (quoted "?and(?installed, ?name(^p1$))"))
                    ("grep" (quoted p1))))
              (pipe
               ("aptitude" search (quoted "?and(?installed, ?name(^p2$))"))
               ("grep" (quoted p2)))
              (pipe
               ("aptitude" search (quoted "?and(?installed, ?name(^p3$))"))
               ("grep" (quoted p3)))
              (not (pipe
                    ("aptitude" search (quoted "?and(?installed, ?name(^p4$))"))
                    ("grep" (quoted "p4"))))))
           (build-script (assoc-in ubuntu-session [:server :packager] :aptitude)
             (exec-script*
              (adjust-packages
               [{:package "p1" :action :remove}
                {:package "p2" :action :install}
                {:package "p3" :action :upgrade}
                {:package "p4" :action :remove :purge true}])))))))
  (testing "aptitude with enable"
    (script/with-script-context [:aptitude]
      (is (script-no-comment=
           (build-script {}
             (exec-checked-script
              "Packages"
              (~lib/package-manager-non-interactive)
              (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
              ("aptitude" install -q -y -t r1 p2+)
              ("aptitude" install -q -y p1+)
              (pipe
               ("aptitude" search (quoted "?and(?installed, ?name(^p1$))"))
               ("grep" (quoted p1)))
              (pipe
               ("aptitude" search (quoted "?and(?installed, ?name(^p2$))"))
               ("grep" (quoted "p2")))))
           (build-script (assoc-in ubuntu-session [:server :packager] :aptitude)
             (exec-script*
              (adjust-packages
               [{:package "p1" :action :install :priority 20}
                {:package "p2" :action :install :enable ["r1"] :priority 2}])))))))
  (testing "aptitude with allow-unsigned"
    (script/with-script-context [:aptitude]
      (is (script-no-comment=
           (build-script {}
             (exec-checked-script
              "Packages"
              (~lib/package-manager-non-interactive)
              (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d"))
              ("aptitude" install -q -y p1+)
              ("aptitude" install -q -y -o "'APT::Get::AllowUnauthenticated=true'" p2+)
              (pipe
               ("aptitude" search (quoted "?and(?installed, ?name(^p1$))"))
               ("grep" (quoted p1)))
              (pipe
               ("aptitude" search (quoted "?and(?installed, ?name(^p2$))"))
               ("grep" (quoted "p2")))))
           (build-script (assoc-in ubuntu-session [:server :packager] :aptitude)
             (exec-script*
              (adjust-packages
               [{:package "p1" :action :install}
                {:package "p2" :action :install :allow-unsigned true}])))))))
  (testing "yum"
    (is (script-no-comment=
         (build-script {}
           (exec-checked-script
            "Packages"
            ("yum" install -q -y p2)
            ("yum" remove -q -y p1 p4)
            ("yum" upgrade -q -y p3)
            ("yum" list installed)))
         (build-script centos-session
           (exec-script*
            (adjust-packages
             [{:package "p1" :action :remove}
              {:package "p2" :action :install}
              {:package "p3" :action :upgrade}
              {:package "p4" :action :remove :purge true}]))))))
  (testing "yum with disable and priority"
    (is (script-no-comment=
         (build-script {}
           (stevedore/checked-script
            "Packages"
            ("yum" install -q -y "--disablerepo=r1" p2)
            ("yum" install -q -y p1)
            ("yum" list installed)))
         (build-script centos-session
           (adjust-packages
            [{:package "p1" :action :install :priority 50}
             {:package "p2" :action :install :disable ["r1"]
              :priority 25}]))))
    (is (script-no-comment=
         (first
          (build-actions centos-session
            (exec-checked-script
             "Packages"
             ("yum" install -q -y p1)
             ("yum" list installed))
            (exec-checked-script
             "Packages"
             ("yum" install -q -y "--disablerepo=r1" p2)
             ("yum" list installed))))
         (first
          (build-actions centos-session
            (package "p1")
            (package "p2" :disable ["r1"] :priority 25)))))))

(deftest add-rpm-test
  (is (script-no-comment=
       (build-script centos-session
         (remote-file "jpackage-utils-compat" :url "http:url")
         (exec-checked-script
          "Install rpm jpackage-utils-compat"
          (if-not ("rpm" -q @("rpm" -pq "jpackage-utils-compat")
                   > "/dev/null" "2>&1")
            (do ("rpm" -U --quiet "jpackage-utils-compat")))))
       (build-script centos-session
         (add-rpm "jpackage-utils-compat" :url "http:url")))))

(deftest debconf-set-selections-test
  (is (script-no-comment=
       (first
        (build-actions {}
          (exec-checked-script
           "Preseed a b c d"
           (pipe (println (quoted "a b c d"))
                 ("/usr/bin/debconf-set-selections")))))
       (first
        (build-actions {}
          (debconf-set-selections {:line "a b c d"})))))
  (is (script-no-comment=
       (first
        (build-actions {}
          (exec-checked-script
           "Preseed p q :select true"
           (pipe (println (quoted "p q select true"))
                 ("/usr/bin/debconf-set-selections")))))
       (first
        (build-actions {}
          (debconf-set-selections
           {:package "p"
            :question "q"
            :type :select
            :value true}))))))
