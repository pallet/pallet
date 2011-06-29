(ns pallet.action.package-test
  (:use pallet.action.package)
  (:use [pallet.stevedore :only [script]]
        clojure.test)
  (:require
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :as logutils]
   [pallet.core :as core]
   [pallet.execute :as execute]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.test-utils :as test-utils]
   [clojure.contrib.io :as io]))

(use-fixtures
 :each
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language)

(use-fixtures
 :once
 (logutils/logging-threshold-fixture))

(def remote-file* (action/action-fn remote-file/remote-file-action))
(def sed* (action/action-fn file/sed))

(deftest test-install-example
  (testing "aptitude"
    (is (= (first
            (build-actions/build-actions
             {}
             (exec-script/exec-checked-script
              "Packages"
              (~lib/package-manager-non-interactive)
              "aptitude install -q -y java+ rubygems+ git- ruby_"
              (aptitude search (quoted "~i")))))
           (first
            (build-actions/build-actions
             {}
             (package "java" :action :install)
             (package "rubygems")
             (package "git" :action :remove)
             (package "ruby" :action :remove :purge true))))))
  (testing "yum"
    (is (= (first
            (build-actions/build-actions
             {:server {:tag :n :image {:os-family :centos}}}
             (exec-script/exec-checked-script
              "Packages"
              "yum install -q -y java rubygems"
              "yum remove -q -y git ruby"
              "yum upgrade -q -y maven2"
              (yum list installed))))
           (first
            (build-actions/build-actions
             {:server {:tag :n :image {:os-family :centos}}}
             (package "java" :action :install)
             (package "rubygems")
             (package "maven2" :action :upgrade)
             (package "git" :action :remove)
             (package "ruby" :action :remove :purge true))))))
  (testing "pacman"
    (is (= (first
            (build-actions/build-actions
             {:server {:tag :n :image {:os-family :arch}}}
             (exec-script/exec-checked-script
              "Packages"
              "pacman -S --noconfirm --noprogressbar java"
              "pacman -S --noconfirm --noprogressbar rubygems"
              "pacman -S --noconfirm --noprogressbar maven2"
              "pacman -R --noconfirm git"
              "pacman -R --noconfirm --nosave ruby")))
           (first
            (build-actions/build-actions
             {:server {:tag :n :image {:os-family :arch}}}
             (package "java" :action :install)
             (package "rubygems")
             (package "maven2" :action :upgrade)
             (package "git" :action :remove)
             (package "ruby" :action :remove :purge true)))))))

(deftest package-manager-non-interactive-test
  (is (= "{ debconf-set-selections <<EOF
debconf debconf/frontend select noninteractive
debconf debconf/frontend seen false
EOF
}"
         (script/with-script-context [:aptitude]
           (stevedore/script (~lib/package-manager-non-interactive))))))

(deftest add-scope-test
  (is (= (stevedore/chained-script
          (set! tmpfile @(mktemp -t addscopeXXXX))
          (cp -p "/etc/apt/sources.list" @tmpfile)
          (awk "'{if ($1 ~ /^deb/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'" "/etc/apt/sources.list" > @tmpfile)
          (mv -f @tmpfile "/etc/apt/sources.list"))
         (add-scope* "deb" "multiverse" "/etc/apt/sources.list")))

  (testing "with sources.list"
    (let [tmp (java.io.File/createTempFile "package_test" "test")]
      (io/copy "deb http://archive.ubuntu.com/ubuntu/ karmic main restricted
deb-src http://archive.ubuntu.com/ubuntu/ karmic main restricted"
            tmp)
      (is (= {:exit 0, :out "", :err ""}
             (execute/sh-script
              (add-scope* "deb" "multiverse" (.getPath tmp)))))
      (is
       (=
        (str "deb http://archive.ubuntu.com/ubuntu/ karmic main restricted  "
             "multiverse \ndeb-src http://archive.ubuntu.com/ubuntu/ karmic "
             "main restricted  multiverse \n")
             (slurp (.getPath tmp))))
      (.delete tmp))))

(deftest package-manager*-test
  (is (= (stevedore/checked-script
          "package-manager multiverse "
          (set! tmpfile @(mktemp -t addscopeXXXX))
          (cp -p "/etc/apt/sources.list" @tmpfile)
          (awk "'{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'" "/etc/apt/sources.list" > @tmpfile)
          (mv -f @tmpfile "/etc/apt/sources.list"))
         (package-manager* test-utils/ubuntu-session :multiverse)))
  (is (= (stevedore/checked-script
          "package-manager update "
          (chain-or
           (aptitude update)
           true))
         (script/with-script-context [:aptitude]
           (package-manager* test-utils/ubuntu-session :update)))))

(deftest package-manager-update-test
  (testing "yum"
    (is (= (first
            (build-actions/build-actions
             {:server {:group-name :n :image {:os-family :centos}}}
             (exec-script/exec-checked-script
              "package-manager update :enable [\"r1\"]"
              (yum makecache -q "--enablerepo=r1"))))
           (first
            (build-actions/build-actions
             {:server {:group-name :n :image {:os-family :centos}}}
             (package-manager :update :enable ["r1"])))))))

(deftest package-manager-configure-test
  (testing "aptitude"
    (is (= (first
            (build-actions/build-actions
             {}
             (exec-script/exec-checked-script
              "package-manager configure :proxy http://192.168.2.37:3182"
              ~(remote-file*
                {}
                "/etc/apt/apt.conf.d/50pallet"
                :content "ACQUIRE::http::proxy \"http://192.168.2.37:3182\";"
                :literal true))))
           (first
            (build-actions/build-actions
             {}
             (package-manager
              :configure :proxy "http://192.168.2.37:3182"))))))
  (testing "yum"
    (is (= (first
            (build-actions/build-actions
             {:server {:tag :n :image {:os-family :centos}}}
             (exec-script/exec-checked-script
              "package-manager configure :proxy http://192.168.2.37:3182"
              ~(remote-file*
                {}
                "/etc/yum.pallet.conf"
                :content "proxy=http://192.168.2.37:3182"
                :literal true)
              (if (not @("fgrep" "yum.pallet.conf" "/etc/yum.conf"))
                (do
                  ("cat" ">>" "/etc/yum.conf" " <<'EOFpallet'")
                  "include=file:///etc/yum.pallet.conf"
                  "EOFpallet")))))
           (first
            (build-actions/build-actions
             {:server {:tag :n :image {:os-family :centos}}}
             (package-manager
              :configure :proxy "http://192.168.2.37:3182"))))))
  (testing "pacman"
    (is (= (first
            (build-actions/build-actions
             {:server {:tag :n :image {:os-family :arch}}}
             (exec-script/exec-checked-script
              "package-manager configure :proxy http://192.168.2.37:3182"
              ~(remote-file*
                {}
                "/etc/pacman.pallet.conf"
                :content (str "XferCommand = /usr/bin/wget "
                              "-e \"http_proxy = http://192.168.2.37:3182\" "
                              "-e \"ftp_proxy = http://192.168.2.37:3182\" "
                              "--passive-ftp --no-verbose -c -O %o %u")
                :literal true)
              (if (not @("fgrep" "pacman.pallet.conf" "/etc/pacman.conf"))
                (do
                  ~(sed*
                    {}
                    "/etc/pacman.conf"
                    "a Include = /etc/pacman.pallet.conf"
                    :restriction "/\\[options\\]/"))))))
           (first
            (build-actions/build-actions
             {:server {:tag :n :image {:os-family :arch}}}
             (package-manager
              :configure :proxy "http://192.168.2.37:3182")))))))

(deftest add-multiverse-example-test
  (is (=  (str
           (stevedore/checked-script
            "package-manager multiverse "
            (set! tmpfile @(mktemp -t addscopeXXXX))
            (~lib/cp "/etc/apt/sources.list" @tmpfile :preserve true)
            (awk "'{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'" "/etc/apt/sources.list" > @tmpfile)
            (~lib/mv @tmpfile "/etc/apt/sources.list" :force true))
           (stevedore/checked-script
            "package-manager update "
            (chain-or
             (aptitude update "")
             true)))
          (first (build-actions/build-actions
                  {}
                  (package-manager :multiverse)
                  (package-manager :update))))))

(deftest package-source*-test
  (let [a (core/group-spec "a" :packager :aptitude)
        b (core/group-spec "b" :packager :yum)]
    (is (=
         (stevedore/checked-commands
          "Package source"
          (remote-file*
           {:server a}
           "/etc/apt/sources.list.d/source1.list"
           :content "deb http://somewhere/apt $(lsb_release -c -s) main\n"))
         (package-source*
          {:server a}
          "source1"
          :aptitude {:url "http://somewhere/apt" :scopes ["main"]}
          :yum {:url "http://somewhere/yum"})))
    (is
     (=
      (stevedore/checked-commands
       "Package source"
       (remote-file*
        {:server b}
        "/etc/yum.repos.d/source1.repo"
        :content
        "[source1]\nname=source1\nbaseurl=http://somewhere/yum\ngpgcheck=0\nenabled=1\n"
        :literal true))
      (package-source*
       {:server b}
       "source1"
       :aptitude {:url "http://somewhere/apt"
                  :scopes ["main"]}
       :yum {:url "http://somewhere/yum"})))
    (is (= (first
            (build-actions/build-actions
             {}
             (exec-script/exec-checked-script
              "Package source"
              (~lib/install-package "python-software-properties")
              (add-apt-repository "ppa:abc"))))
           (first
            (build-actions/build-actions
             {}
             (package-source
              "source1"
              :aptitude {:url "ppa:abc"}
              :yum {:url "http://somewhere/yum"})))))
    (is (= (stevedore/checked-commands
            "Package source"
            (remote-file*
             {:server a}
             "/etc/apt/sources.list.d/source1.list"
             :content "deb http://somewhere/apt $(lsb_release -c -s) main\n")
            (stevedore/script
             (apt-key adv "--keyserver" subkeys.pgp.net "--recv-keys" 1234)))
           (package-source*
            {:server a}
            "source1"
            :aptitude {:url "http://somewhere/apt"
                       :scopes ["main"]
                       :key-id 1234}
            :yum {:url "http://somewhere/yum"})))))

(deftest package-source-test
  (let [a (core/group-spec "a" :packager :aptitude)
        b (core/group-spec "b" :packager :yum)]
    (is (= (stevedore/checked-commands
            "Package source"
            (remote-file*
             {:server a}
             "/etc/apt/sources.list.d/source1.list"
             :content "deb http://somewhere/apt $(lsb_release -c -s) main\n"))
           (first (build-actions/build-actions
                   {:server a}
                   (package-source
                    "source1"
                    :aptitude {:url "http://somewhere/apt"
                               :scopes ["main"]}
                    :yum {:url "http://somewhere/yum"})))))
    (is (= (stevedore/checked-commands
            "Package source"
            (remote-file*
             {:server b}
             "/etc/yum.repos.d/source1.repo"
             :content "[source1]\nname=source1\nbaseurl=http://somewhere/yum\ngpgcheck=0\nenabled=1\n"
             :literal true))
           (first (build-actions/build-actions
                   {:server b}
                   (package-source
                    "source1"
                    :aptitude {:url "http://somewhere/apt"
                               :scopes ["main"]}
                    :yum {:url "http://somewhere/yum"})))))))

(deftest packages-test
  (let [a (core/group-spec "a" :packager :aptitude)
        b (core/group-spec "b" :packager :yum)]
    (is (= (first
            (build-actions/build-actions
             {:server a}
             (package "git-apt")
             (package "git-apt2")))
           (first (build-actions/build-actions
                   {}
                   (packages
                    :aptitude ["git-apt" "git-apt2"]
                    :yum ["git-yum"])))))
    (is (= (first
            (build-actions/build-actions
             {:server b}
             (package "git-yum")))
           (first (build-actions/build-actions
                   {:server b}
                   (packages
                    :aptitude ["git-apt"]
                    :yum ["git-yum"])))))))

(deftest ordering-test
  (testing "package-source alway precedes packages"
    (is (= (first
            (build-actions/build-actions
             {}
             (package-source "s" :aptitude {:url "http://somewhere/apt"})
             (package "p")))
           (first
            (build-actions/build-actions
             {}
             (package "p")
             (package-source "s" :aptitude {:url "http://somewhere/apt"}))))))

  (testing "package-manager alway precedes packages"
    (is (= (first
            (build-actions/build-actions
             {}
             (package-manager :update)
             (package "p")))
           (first
            (build-actions/build-actions
             {}
             (package "p")
             (package-manager :update))))))

  (testing "package-source alway precedes packages and package-manager"
    (is (= (first
            (build-actions/build-actions
             {}
             (package-source "s" :aptitude {:url "http://somewhere/apt"})
             (package-manager :update)
             (package "p")))
           (first
            (build-actions/build-actions
             {}
             (package "p")
             (package-manager :update)
             (package-source "s" :aptitude {:url "http://somewhere/apt"})))))))

(deftest adjust-packages-test
  (testing "aptitude"
    (script/with-script-context [:aptitude]
      (is (= (stevedore/checked-script
              "Packages"
              (~lib/package-manager-non-interactive)
              (aptitude install -q -y p1- p4_ p2+ p3+)
              (aptitude search (quoted "~i")))
             (adjust-packages
              {:server {:packager :aptitude}}
              [{:package "p1" :action :remove}
               {:package "p2" :action :install}
               {:package "p3" :action :upgrade}
               {:package "p4" :action :remove :purge true}])))))
  (testing "aptitude with enable"
    (script/with-script-context [:aptitude]
      (is (= (stevedore/checked-script
              "Packages"
              (~lib/package-manager-non-interactive)
              (aptitude install -q -y -t r1 p2+)
              (aptitude install -q -y p1+)
              (aptitude search (quoted "~i")))
             (adjust-packages
              {:server {:packager :aptitude}}
              [{:package "p1" :action :install :priority 20}
               {:package "p2" :action :install :enable ["r1"] :priority 2}])))))
  (testing "yum"
    (is (= (stevedore/checked-script
            "Packages"
            (yum install -q -y p2)
            (yum remove -q -y p1 p4)
            (yum upgrade -q -y p3)
            (yum list installed))
           (script/with-script-context [:yum]
             (adjust-packages
              {:server {:packager :yum}}
              [{:package "p1" :action :remove}
               {:package "p2" :action :install}
               {:package "p3" :action :upgrade}
               {:package "p4" :action :remove :purge true}])))))
  (testing "yum with disable and priority"
    (is (= (stevedore/checked-script
            "Packages"
            (yum install -q -y "--disablerepo=r1" p2)
            (yum install -q -y p1)
            (yum list installed))
           (script/with-script-context [:yum]
             (adjust-packages
              {:server {:packager :yum}}
              [{:package "p1" :action :install :priority 50}
               {:package "p2" :action :install :disable ["r1"]
                :priority 25}]))))
    (is (= (stevedore/checked-script
            "Packages"
            (yum install -q -y "--disablerepo=r1" p2)
            (yum install -q -y p1)
            (yum list installed))
           (first
            (build-actions/build-actions
             {:packager :yum}
             (package "p1")
             (package "p2" :disable ["r1"] :priority 25)))))))

(deftest add-rpm-test
  (is (=
       (first
        (build-actions/build-actions
         {:server {:packager :yum}}
         (remote-file/remote-file "jpackage-utils-compat" :url "http:url")
         (exec-script/exec-checked-script
          "Install rpm jpackage-utils-compat"
          (if-not (rpm -q @(rpm -pq "jpackage-utils-compat")
                       > "/dev/null" "2>&1")
            (do (rpm -U --quiet "jpackage-utils-compat"))))))
       (first
        (build-actions/build-actions
         {:server {:packager :yum}}
         (add-rpm "jpackage-utils-compat" :url "http:url"))))))
