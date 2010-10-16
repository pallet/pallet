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
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.target :as target]
   [clojure.contrib.io :as io]))

(use-fixtures :each with-ubuntu-script-template)

(deftest update-package-list-test
  (is (= "aptitude update -q "
         (script/with-template [:aptitude]
           (stevedore/script (update-package-list)))))
  (is (= "yum makecache "
         (script/with-template [:yum]
           (stevedore/script (update-package-list)))))
  (is (= "zypper refresh "
         (script/with-template [:zypper]
           (stevedore/script (update-package-list)))))
  (is (= "pacman -Sy --noconfirm --noprogressbar "
         (script/with-template [:pacman]
           (stevedore/script (update-package-list))))))

(deftest install-package-test
  (is (= "aptitude install -q -y  java && aptitude show java"
         (script/with-template [:aptitude]
           (stevedore/script (install-package "java")))))
  (is (= "yum install -y -q  java"
         (script/with-template [:yum]
           (stevedore/script (install-package "java"))))))


(deftest test-install-example
  (is (= (first
          (build-resources
           []
           (exec-script/exec-checked-script
            "Package java"
            (package-manager-non-interactive)
            "aptitude install -q -y  java && aptitude show java")
           (exec-script/exec-checked-script
            "Package rubygems"
            (package-manager-non-interactive)
            "aptitude install -q -y  rubygems && aptitude show rubygems\n")))
         (first (build-resources
                 []
                 (package "java" :action :install)
                 (package "rubygems"))))))

(deftest package-manager-non-interactive-test
  (is (= "{ debconf-set-selections <<EOF
debconf debconf/frontend select noninteractive
debconf debconf/frontend seen false
EOF
}"
         (script/with-template [:aptitude]
           (stevedore/script (package-manager-non-interactive))))))

(deftest add-scope-test
  (is (= "tmpfile=$(mktemp -t addscopeXXXX)\ncp -p /etc/apt/sources.list ${tmpfile}\nawk '{if ($1 ~ /^deb/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'  /etc/apt/sources.list  >  ${tmpfile}  && mv -f ${tmpfile} /etc/apt/sources.list\n"
         (add-scope "deb" "multiverse" "/etc/apt/sources.list")))

  (testing "with sources.list"
    (let [tmp (java.io.File/createTempFile "package_test" "test")]
      (io/copy "deb http://archive.ubuntu.com/ubuntu/ karmic main restricted
deb-src http://archive.ubuntu.com/ubuntu/ karmic main restricted"
            tmp)
      (is (= {:exit 0, :out "", :err ""}
             (execute/sh-script (add-scope "deb" "multiverse" (.getPath tmp)))))
      (is (= "deb http://archive.ubuntu.com/ubuntu/ karmic main restricted  multiverse \ndeb-src http://archive.ubuntu.com/ubuntu/ karmic main restricted  multiverse \n"
             (slurp (.getPath tmp))))
      (.delete tmp))))

(deftest package-manager*-test
  (is (= "echo \"package-manager...\"\n{ tmpfile=$(mktemp -t addscopeXXXX)\ncp -p /etc/apt/sources.list ${tmpfile}\nawk '{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'  /etc/apt/sources.list  >  ${tmpfile}  && mv -f ${tmpfile} /etc/apt/sources.list; } || { echo package-manager failed ; exit 1 ; } >&2 \necho \"...done\"\n"
         (package-manager* ubuntu-request :multiverse)))
  (is (= "echo \"package-manager...\"\n{ aptitude update -q; } || { echo package-manager failed ; exit 1 ; } >&2 \necho \"...done\"\n"
         (script/with-template [:aptitude]
           (package-manager* ubuntu-request :update)))))

(deftest add-multiverse-example-test
  (is (= "echo \"package-manager...\"\n{ tmpfile=$(mktemp -t addscopeXXXX)\ncp -p /etc/apt/sources.list ${tmpfile}\nawk '{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'  /etc/apt/sources.list  >  ${tmpfile}  && mv -f ${tmpfile} /etc/apt/sources.list; } || { echo package-manager failed ; exit 1 ; } >&2 \necho \"...done\"\necho \"package-manager...\"\n{ aptitude update -q; } || { echo package-manager failed ; exit 1 ; } >&2 \necho \"...done\"\n"
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
      "[source1]\nname=source1\nbaseurl=http://somewhere/yum\ngpgcheck=0\n"))
    (package-source*
     {:node-type b}
     "source1"
     :aptitude {:url "http://somewhere/apt"
                :scopes ["main"]}
     :yum {:url "http://somewhere/yum"})))
  (is (= (stevedore/checked-commands
          "Package source"
          (package/package* {:node-type a} "python-software-properties")
          (stevedore/script (add-apt-repository "ppa:abc")))
         (package-source*
          {:node-type a}
          "source1"
          :aptitude {:url "ppa:abc"}
          :yum {:url "http://somewhere/yum"})))
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
           :content "[source1]\nname=source1\nbaseurl=http://somewhere/yum\ngpgcheck=0\n"))
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

(deftest add-centos55-to-amzn-linux-test
  (core/defnode a {:packager :yum :image {:os-family :amzn-linux}})
  (is (= (first (build-resources
                 [:node-type a]
                 (package "yum-priorities")
                 (package-source
                  "Centos-5.5"
                  :url #'pallet.resource.package/centos-55-repo
                  :gpgkey #'pallet.resource.package/centos-55-repo-key
                  :priority 50)))
         (first (build-resources
                 [:node-type a]
                 (add-centos55-to-amzn-linux))))))
