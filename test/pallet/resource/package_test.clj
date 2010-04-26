(ns pallet.resource.package-test
 (:require pallet.compat)
  (:use [pallet.resource.package] :reload-all)
  (:use [pallet.stevedore :only [script]]
        [pallet.utils :only [sh-script]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.target :as target]))

(pallet.compat/require-contrib)

(deftest update-package-list-test
  (is (= "aptitude update "
         (script (update-package-list)))))

(deftest install-package-test
  (is (= "aptitude install -y  java"
         (script (install-package "java")))))


(deftest test-install-example
  (is (= "debconf-set-selections <<EOF
debconf debconf/frontend select noninteractive
debconf debconf/frontend seen false
EOF
aptitude install -y  java\naptitude install -y  rubygems\n"
         (pallet.resource/build-resources []
          (package "java" :action :install)
          (package "rubygems" :action :install)))))

(deftest package-manager-non-interactive-test
  (is (= "debconf-set-selections <<EOF
debconf debconf/frontend select noninteractive
debconf debconf/frontend seen false
EOF
"
         (script (package-manager-non-interactive)))))

(deftest add-scope-test
  (is (= "tmpfile=$(mktemp addscopeXXXX)\ncp -p /etc/apt/sources.list ${tmpfile}\nawk '{if ($1 ~ /^deb/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'  /etc/apt/sources.list  >  ${tmpfile}  && mv -f ${tmpfile} /etc/apt/sources.list\n"
         (add-scope "deb" "multiverse" "/etc/apt/sources.list")))

  (testing "with sources.list"
    (let [tmp (java.io.File/createTempFile "package_test" "test")]
      (io/copy "deb http://archive.ubuntu.com/ubuntu/ karmic main restricted
deb-src http://archive.ubuntu.com/ubuntu/ karmic main restricted"
            tmp)
      (is (= {:exit 0, :out "", :err ""}
             (sh-script (add-scope "deb" "multiverse" (.getPath tmp)))))
      (is (= "deb http://archive.ubuntu.com/ubuntu/ karmic main restricted  multiverse \ndeb-src http://archive.ubuntu.com/ubuntu/ karmic main restricted  multiverse \n"
             (slurp (.getPath tmp))))
      (.delete tmp))))

(deftest package-manager*-test
  (is (= "tmpfile=$(mktemp addscopeXXXX)\ncp -p /etc/apt/sources.list ${tmpfile}\nawk '{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'  /etc/apt/sources.list  >  ${tmpfile}  && mv -f ${tmpfile} /etc/apt/sources.list\n"
         (package-manager* :multiverse)))
  (is (= "aptitude update "
         (package-manager* :update))))

(deftest add-multiverse-example-test
  (is (= "tmpfile=$(mktemp addscopeXXXX)\ncp -p /etc/apt/sources.list ${tmpfile}\nawk '{if ($1 ~ /^deb.*/ && ! /multiverse/  ) print $0 \" \" \" multiverse \" ; else print; }'  /etc/apt/sources.list  >  ${tmpfile}  && mv -f ${tmpfile} /etc/apt/sources.list\naptitude update\n"
         (pallet.resource/build-resources []
          (package-manager :multiverse)
          (package-manager :update)))))

(deftest package-source*-test
  (core/defnode a [:ubuntu])
  (core/defnode b [:centos])
  (target/with-target nil a
    (is (= "cat > /etc/apt/sources.list.d/source1.list <<EOF\ndeb http://somewhere/apt $(lsb_release -c -s) main\n\nEOF\n\n"
           (package-source*
            "source1"
            :aptitude {:url "http://somewhere/apt"
                       :scopes ["main"]}
            :yum {:url "http://somewhere/yum"}))))
    (target/with-target nil b
    (is (= "cat > /etc/yum.repos.d/source1.repo <<EOF\n[source1]\nname=source1\nbaseurl=http://somewhere/yum\ngpgcheck=0\n\nEOF\n\n"
           (package-source*
            "source1"
            :aptitude {:url "http://somewhere/apt"
                       :scopes ["main"]}
            :yum {:url "http://somewhere/yum"})))))


(deftest package-source-test
  (core/defnode a [:ubuntu])
  (core/defnode b [:centos])
  (target/with-target nil a
    (is (= "cat > /etc/apt/sources.list.d/source1.list <<EOF\ndeb http://somewhere/apt $(lsb_release -c -s) main\n\nEOF\n"
           (resource/build-resources
            []
            (package-source
             "source1"
             :aptitude {:url "http://somewhere/apt"
                        :scopes ["main"]}
             :yum {:url "http://somewhere/yum"})))))
  (target/with-target nil b
    (is (= "cat > /etc/yum.repos.d/source1.repo <<EOF\n[source1]\nname=source1\nbaseurl=http://somewhere/yum\ngpgcheck=0\n\nEOF\n"
           (resource/build-resources
            []
            (package-source
             "source1"
             :aptitude {:url "http://somewhere/apt"
                        :scopes ["main"]}
             :yum {:url "http://somewhere/yum"}))))))
