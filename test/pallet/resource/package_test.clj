(ns pallet.resource.package-test
  (:use pallet.resource.package)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.core :as core]
   [pallet.script :as script]
   [pallet.execute :as execute]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.file :as file]
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.target :as target]
   [clojure.contrib.io :as io]))

(use-fixtures :each with-ubuntu-script-template)

(deftest update-package-list-test
  (is (= "aptitude update || true"
         (script/with-template [:aptitude]
           (stevedore/script (update-package-list)))))
  (is (= "yum makecache -q"
         (script/with-template [:yum]
           (stevedore/script (update-package-list)))))
  (is (= "zypper refresh"
         (script/with-template [:zypper]
           (stevedore/script (update-package-list)))))
  (is (= "pacman -Sy --noconfirm --noprogressbar"
         (script/with-template [:pacman]
           (stevedore/script (update-package-list))))))

(deftest upgrade-all-packages-test
  (is (= "aptitude upgrade -q -y"
         (script/with-template [:aptitude]
           (stevedore/script (upgrade-all-packages)))))
  (is (= "yum update -y -q"
         (script/with-template [:yum]
           (stevedore/script (upgrade-all-packages)))))
  (is (= "zypper update -y"
         (script/with-template [:zypper]
           (stevedore/script (upgrade-all-packages)))))
  (is (= "pacman -Su --noconfirm --noprogressbar"
         (script/with-template [:pacman]
           (stevedore/script (upgrade-all-packages))))))

(deftest install-package-test
  (is (= "aptitude install -q -y java && aptitude show java"
         (script/with-template [:aptitude]
           (stevedore/script (install-package "java")))))
  (is (= "yum install -y -q java"
         (script/with-template [:yum]
           (stevedore/script (install-package "java"))))))

(deftest list-installed-packages-test
  (is (= "aptitude search \"~i\""
         (script/with-template [:aptitude]
           (stevedore/script (list-installed-packages)))))
  (is (= "yum list installed"
         (script/with-template [:yum]
           (stevedore/script (list-installed-packages))))))


(deftest test-install-example
  (testing "aptitude"
    (is (= (first
            (build-resources
             []
             (exec-script/exec-checked-script
              "Packages"
              (package-manager-non-interactive)
              "aptitude install -q -y java+ rubygems+ git- ruby_"
              (aptitude search (quoted "~i")))))
           (first
            (build-resources
             []
             (package "java" :action :install)
             (package "rubygems")
             (package "git" :action :remove)
             (package "ruby" :action :remove :purge true))))))
  (testing "yum"
    (is (= (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (exec-script/exec-checked-script
              "Packages"
              "yum install -q -y java rubygems"
              "yum remove -q -y git ruby"
              "yum upgrade -q -y maven2"
              (yum list installed))))
           (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (package "java" :action :install)
             (package "rubygems")
             (package "maven2" :action :upgrade)
             (package "git" :action :remove)
             (package "ruby" :action :remove :purge true))))))
  (testing "pacman"
    (is (= (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :arch}}]
             (exec-script/exec-checked-script
              "Packages"
              "pacman -S --noconfirm --noprogressbar java"
              "pacman -S --noconfirm --noprogressbar rubygems"
              "pacman -S --noconfirm --noprogressbar maven2"
              "pacman -R --noconfirm git"
              "pacman -R --noconfirm --nosave ruby")))
           (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :arch}}]
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
         (script/with-template [:aptitude]
           (stevedore/script (package-manager-non-interactive))))))

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
      (is (= "deb http://archive.ubuntu.com/ubuntu/ karmic main restricted  multiverse \ndeb-src http://archive.ubuntu.com/ubuntu/ karmic main restricted  multiverse \n"
             (slurp (.getPath tmp))))
      (.delete tmp))))

(deftest package-manager*-test
  (is (= (stevedore/checked-script
          "package-manager"
          (set! tmpfile @(mktemp -t addscopeXXXX))
          (cp -p "/etc/apt/sources.list" @tmpfile)
          (awk "'{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'" "/etc/apt/sources.list" > @tmpfile)
          (mv -f @tmpfile "/etc/apt/sources.list"))
         (package-manager* ubuntu-request :multiverse)))
  (is (= (stevedore/checked-script
          "package-manager"
          (chain-or
           (aptitude update "")
           true))
         (script/with-template [:aptitude]
           (package-manager* ubuntu-request :update)))))

(deftest package-manager-configure-test
  (testing "aptitude"
    (is (= (first
            (build-resources
             []
             (exec-script/exec-checked-script
              "package-manager"
              ~(remote-file/remote-file*
                {}
                "/etc/apt/apt.conf.d/50pallet"
                :content "ACQUIRE::http::proxy \"http://192.168.2.37:3182\";"
                :literal true))))
           (first
            (build-resources
             []
             (package-manager
              :configure :proxy "http://192.168.2.37:3182"))))))
  (testing "yum"
    (is (= (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (exec-script/exec-checked-script
              "package-manager"
              ~(remote-file/remote-file*
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
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (package-manager
              :configure :proxy "http://192.168.2.37:3182"))))))
  (testing "pacman"
    (is (= (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :arch}}]
             (exec-script/exec-checked-script
              "package-manager"
              ~(remote-file/remote-file*
                {}
                "/etc/pacman.pallet.conf"
                :content (str "XferCommand = /usr/bin/wget "
                              "-e \"http_proxy = http://192.168.2.37:3182\" "
                              "-e \"ftp_proxy = http://192.168.2.37:3182\" "
                              "--passive-ftp --no-verbose -c -O %o %u")
                :literal true)
              (if (not @("fgrep" "pacman.pallet.conf" "/etc/pacman.conf"))
                (do
                  ~(file/sed*
                    {}
                    "/etc/pacman.conf"
                    "a Include = /etc/pacman.pallet.conf"
                    :restriction "/\\[options\\]/"))))))
           (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :arch}}]
             (package-manager
              :configure :proxy "http://192.168.2.37:3182")))))))

(deftest add-multiverse-example-test
  (is (=  (str
           (stevedore/checked-script
            "package-manager"
            (set! tmpfile @(mktemp -t addscopeXXXX))
            (cp -p "/etc/apt/sources.list" @tmpfile)
            (awk "'{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'" "/etc/apt/sources.list" > @tmpfile)
            (mv -f @tmpfile "/etc/apt/sources.list"))
           (stevedore/checked-script
            "package-manager"
            (chain-or
             (aptitude update "")
             true)))
          (first (build-resources
                  []
                  (package-manager :multiverse)
                  (package-manager :update))))))

(deftest package-source*-test
  (core/defnode a {:packager :aptitude})
  (core/defnode b {:packager :yum})
  (is (=
       (stevedore/checked-commands
        "Package source"
        (remote-file/remote-file*
         {:node-type a}
         "/etc/apt/sources.list.d/source1.list"
         :content "deb http://somewhere/apt $(lsb_release -c -s) main\n"))
       (package-source*
        {:node-type a}
        "source1"
        :aptitude {:url "http://somewhere/apt" :scopes ["main"]}
        :yum {:url "http://somewhere/yum"})))
  (is
   (=
    (stevedore/checked-commands
     "Package source"
     (remote-file/remote-file*
      {:node-type b}
      "/etc/yum.repos.d/source1.repo"
      :content
      "[source1]\nname=source1\nbaseurl=http://somewhere/yum\ngpgcheck=0\nenabled=1\n"
      :literal true))
    (package-source*
     {:node-type b}
     "source1"
     :aptitude {:url "http://somewhere/apt"
                :scopes ["main"]}
     :yum {:url "http://somewhere/yum"})))
  (is (= (first
          (build-resources
           []
           (exec-script/exec-checked-script
            "Package source"
            (install-package "python-software-properties")
            (add-apt-repository "ppa:abc"))))
         (first
          (build-resources
           []
           (package-source
            "source1"
            :aptitude {:url "ppa:abc"}
            :yum {:url "http://somewhere/yum"})))))
  (is (= (stevedore/checked-commands
          "Package source"
          (remote-file/remote-file*
           {:node-type a}
           "/etc/apt/sources.list.d/source1.list"
           :content "deb http://somewhere/apt $(lsb_release -c -s) main\n")
          (stevedore/script
           (apt-key adv "--keyserver" subkeys.pgp.net "--recv-keys" 1234)))
         (package-source*
          {:node-type a}
          "source1"
          :aptitude {:url "http://somewhere/apt"
                     :scopes ["main"]
                     :key-id 1234}
          :yum {:url "http://somewhere/yum"}))))

(deftest package-source-test
  (core/defnode a {:packager :aptitude})
  (core/defnode b {:packager :yum})
  (is (= (stevedore/checked-commands
          "Package source"
          (remote-file/remote-file*
           {:node-type a}
           "/etc/apt/sources.list.d/source1.list"
           :content "deb http://somewhere/apt $(lsb_release -c -s) main\n"))
         (first (build-resources
                 [:node-type a]
                 (package-source
                  "source1"
                  :aptitude {:url "http://somewhere/apt"
                             :scopes ["main"]}
                  :yum {:url "http://somewhere/yum"})))))
  (is (= (stevedore/checked-commands
          "Package source"
          (remote-file/remote-file*
           {:node-type b}
           "/etc/yum.repos.d/source1.repo"
           :content "[source1]\nname=source1\nbaseurl=http://somewhere/yum\ngpgcheck=0\nenabled=1\n"
           :literal true))
         (first (build-resources
                 [:node-type b]
                 (package-source
                  "source1"
                  :aptitude {:url "http://somewhere/apt"
                             :scopes ["main"]}
                  :yum {:url "http://somewhere/yum"}))))))

(deftest packages-test
  (core/defnode a {:packager :aptitude})
  (core/defnode b {:packager :yum})
  (is (= (first
           (build-resources
            [:node-type a]
            (package "git-apt")
            (package "git-apt2")))
         (first (build-resources
                 []
                 (packages
                  :aptitude ["git-apt" "git-apt2"]
                  :yum ["git-yum"])))))
  (is (= (first
           (build-resources
            [:node-type b]
            (package "git-yum")))
         (first (build-resources
                 [:node-type b]
                 (packages
                  :aptitude ["git-apt"]
                  :yum ["git-yum"]))))))

(deftest ordering-test
  (testing "package-source alway precedes packages"
    (is (= (first
            (build-resources
             []
             (package-source "s" :aptitude {:url "http://somewhere/apt"})
             (package "p")))
           (first
            (build-resources
             []
             (package "p")
             (package-source "s" :aptitude {:url "http://somewhere/apt"}))))))

  (testing "package-manager alway precedes packages"
    (is (= (first
            (build-resources
             []
             (package-manager :update)
             (package "p")))
           (first
            (build-resources
             []
             (package "p")
             (package-manager :update))))))

  (testing "package-source alway precedes packages and package-manager"
    (is (= (first
            (build-resources
             []
             (package-source "s" :aptitude {:url "http://somewhere/apt"})
             (package-manager :update)
             (package "p")))
           (first
            (build-resources
             []
             (package "p")
             (package-manager :update)
             (package-source "s" :aptitude {:url "http://somewhere/apt"})))))))

(deftest adjust-packages-test
  (testing "aptitude"
    (script/with-template [:aptitude]
      (is (= (stevedore/checked-script
              "Packages"
              (package-manager-non-interactive)
              (aptitude install -q -y p1- p4_ p2+ p3+)
              (aptitude search (quoted "~i")))
             (adjust-packages
              {:target-packager :aptitude}
              [{:package "p1" :action :remove}
               {:package "p2" :action :install}
               {:package "p3" :action :upgrade}
               {:package "p4" :action :remove :purge true}])))))
  (testing "yum"
    (is (= (stevedore/checked-script
            "Packages"
            (yum install -q -y p2)
            (yum remove -q -y p1 p4)
            (yum upgrade -q -y p3)
            (yum list installed))
           (script/with-template [:yum]
             (adjust-packages
              {:target-packager :yum}
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
           (script/with-template [:yum]
             (adjust-packages
              {:target-packager :yum}
              [{:package "p1" :action :install :priority 50}
               {:package "p2" :action :install :disable ["r1"]
                :priority 25}]))))
    (is (= (stevedore/checked-script
            "Packages"
            (yum install -q -y "--disablerepo=r1" p2)
            (yum install -q -y p1)
            (yum list installed))
           (first
            (build-resources
             [:target-packager :yum]
             (package "p1")
             (package "p2" :disable ["r1"] :priority 25)))))))
