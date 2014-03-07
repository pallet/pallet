(ns pallet.script.lib-test
  (:refer-clojure :exclude [alias source])
  (:require
   [clojure.test :refer :all]
   [pallet.script :as script]
   [pallet.script.lib :refer :all]
   [pallet.stevedore :refer [fragment map-to-arg-string script]]
   [pallet.test-utils :as test-utils]))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language
 test-utils/with-no-source-line-comments)


(deftest exit-test
  (is (=
       "exit 1"
       (script (~exit 1)))))

(deftest rm-test
  (is (=
       "rm --force file1"
       (script (~rm "file1" :force true)))))

(deftest mv-test
  (is (=
       "mv --backup=\"numbered\" file1 file2"
       (script (~mv "file1" "file2" :backup :numbered)))))

(deftest ln-test
  (is (=
       "ln -s file1 file2"
       (script (~ln "file1" "file2" :symbolic true)))))

(deftest chown-test
  (is (=
       "chown user1 file1"
       (script (~chown "user1" "file1")))))

(deftest chgrp-test
  (is (=
       "chgrp group1 file1"
       (script (~chgrp "group1" "file1")))))

(deftest chmod-test
  (is (=
       "chmod 0666 file1"
       (script (~chmod "0666" "file1")))))

(deftest tmpdir-test
  (is (= "${TMPDIR:-${TEMP:-${TMP:-$(if [ -d /tmp ]; then echo /tmp;else
if [ -d /var/tmp ]; then echo /var/tmp;else
if [ -d /use/tmp ]; then echo /usr/tmp;fi
fi
fi)}}}"
         (fragment (~tmp-dir)))))

(deftest normalise-md5-test
  (is (=
       (script
        (if ("egrep" "'^[a-fA-F0-9]+$'" abc.md5)
          (println
           (quoted (str "  " @(pipe ("basename" abc.md5)
                                    ("sed" -e "s/.md5//"))))
           ">>" abc.md5))
        ("sed" -i -e (quoted "s_/.*/\\(..*\\)_\\1_") "abc.md5"))
       (script (~normalise-md5 abc.md5)))))

(deftest md5sum-verify-test
  (is (=
       (script
        ("(" (chain-and
              ("cd" @("dirname" abc.md5))
              ("md5sum"
               ~(map-to-arg-string {:quiet true :check true})
               @("basename" abc.md5))) ")"))
       (script (~md5sum-verify abc.md5)))))

(deftest heredoc-test
  (is (=
       "{ cat > somepath <<EOFpallet\nsomecontent\nEOFpallet\n }"
       (script (~heredoc "somepath" "somecontent" {})))))

(deftest heredoc-literal-test
  (is (=
       "{ cat > somepath <<'EOFpallet'\nsomecontent\nEOFpallet\n }"
       (script (~heredoc "somepath" "somecontent" {:literal true})))))

(deftest sed-file-test
  (testing "explicit separator"
    (is (=
         "sed -i -e \"s|a|b|\" path"
         (script (~sed-file "path" {"a" "b"} {:seperator "|"})))))
  (testing "single quotings"
    (is (=
         "sed -i -e 's/a/b/' path"
         (script (~sed-file "path" {"a" "b"} {:quote-with "'"})))))
  (testing "computed separator"
    (is (=
         "sed -i -e \"s/a/b/\" path"
         (script (~sed-file "path" {"a" "b"} {}))))
    (is (=
         "sed -i -e \"s_a/_b_\" path"
         (script (~sed-file "path" {"a/" "b"} {}))))
    (is (=
         "sed -i -e \"s_a_b/_\" path"
         (script (~sed-file "path" {"a" "b/"} {}))))
    (is (=
         "sed -i -e \"s*/_|:%!@*b*\" path"
         (script (~sed-file "path" {"/_|:%!@" "b"} {})))))
  (testing "restrictions"
    (is (=
         "sed -i -e \"1 s/a/b/\" path"
         (script (~sed-file "path" {"a" "b"} {:restriction "1"}))))
    (is (=
         "sed -i -e \"/a/ s/a/b/\" path"
         (script (~sed-file "path" {"a" "b"} {:restriction "/a/"})))))
  (testing "other commands"
    (is (=
         "sed -i -e \"1 a\" path"
         (script (~sed-file "path" "a" {:restriction "1"}))))))

(deftest make-temp-file-test
  (is (=
       "$(mktemp \"prefixXXXXX\")"
       (script (~make-temp-file "prefix")))))

(deftest download-file-test
  (is (script (~download-file "http://server.com/" "/path")))
  (is (=
       "if hash curl 2>&-; then curl -o \"/path\" --retry 5 --silent --show-error --fail --location --proxy localhost:3812 \"http://server.com/\";else\nif hash wget 2>&-; then wget -O \"/path\" --tries 5 --no-verbose --progress=dot:mega -e \"http_proxy = http://localhost:3812\" -e \"ftp_proxy = http://localhost:3812\" \"http://server.com/\";else\necho No download utility available\nexit 1\nfi\nfi"
       (script
        (~download-file
         "http://server.com/" "/path" :proxy "http://localhost:3812"))))
  (is (=
       "if hash curl 2>&-; then curl -o \"/path\" --retry 5 --silent --show-error --fail --location --proxy localhost:3812 --insecure \"http://server.com/\";else\nif hash wget 2>&-; then wget -O \"/path\" --tries 5 --no-verbose --progress=dot:mega -e \"http_proxy = http://localhost:3812\" -e \"ftp_proxy = http://localhost:3812\" --no-check-certificate \"http://server.com/\";else\necho No download utility available\nexit 1\nfi\nfi"
       (script
        (~download-file
         "http://server.com/" "/path" :proxy "http://localhost:3812"
         :insecure true)))
      ":insecure should disable ssl checks"))

(deftest download-request-test
  (is (=
       "curl -o \"p\" --retry 3 --silent --show-error --fail --location -H \"n: v\" \"http://server.com\""
       (let [request {:headers {"n" "v"}
                      :endpoint (java.net.URI. "http://server.com")}]
         (script (~download-request "p" ~request))))))

(deftest mkdir-test
  (is (=
       "mkdir -p dir"
       (script (~mkdir "dir" :path ~true)))))

;;; user management

(deftest create-user-test
  (is (=
       "/usr/sbin/useradd --create-home user1"
       (script (~create-user "user1"  ~{:create-home true}))))
  (is (=
       "/usr/sbin/useradd --system user1"
       (script (~create-user "user1"  ~{:system true}))))
  (testing "groups"
    (is (=
         "/usr/sbin/useradd --groups \"g1,g2\" user1"
         (script (create-user "user1" {:groups [g1 g2]}))))
    (is (=
         "/usr/sbin/useradd --groups \"g1,g2\" user1"
         (script (create-user "user1" ~{:groups ["g1" "g2"]})))))
  (testing "system on rh"
    (script/with-script-context [:centos]
      (is (=
           "/usr/sbin/useradd -r user1"
           (script (~create-user "user1"  ~{:system true})))))))

(deftest modify-user-test
  (is (=
       "/usr/sbin/usermod --home \"/home2/user1\" --shell \"/bin/bash\" user1"
       (script
        (~modify-user
         "user1"  ~{:home "/home2/user1" :shell "/bin/bash"})))))


;;; package management

(deftest update-package-list-test
  (is (=
       "aptitude update -q=2 -y || true"
       (script/with-script-context [:aptitude]
         (script (~update-package-list)))))
  (is (=
       "yum makecache -q"
       (script/with-script-context [:yum]
         (script (~update-package-list)))))
  (is (=
       "zypper refresh"
       (script/with-script-context [:zypper]
         (script (~update-package-list)))))
  (is (=
       "pacman -Sy --noconfirm --noprogressbar"
       (script/with-script-context [:pacman]
         (script (~update-package-list))))))

(deftest upgrade-all-packages-test
  (is (=
       "aptitude upgrade -q -y"
       (script/with-script-context [:aptitude]
         (script (~upgrade-all-packages)))))
  (is (=
       "yum update -y -q"
       (script/with-script-context [:yum]
         (script (~upgrade-all-packages)))))
  (is (=
       "zypper update -y"
       (script/with-script-context [:zypper]
         (script (~upgrade-all-packages)))))
  (is (=
       "pacman -Su --noconfirm --noprogressbar"
       (script/with-script-context [:pacman]
         (script (~upgrade-all-packages))))))

(deftest install-package-test
  (is (=
       "aptitude install -q -y java && aptitude show java"
       (script/with-script-context [:aptitude]
         (script (~install-package "java")))))
  (is (=
       "yum install -y -q java"
       (script/with-script-context [:yum]
         (script (~install-package "java"))))))

(deftest list-installed-packages-test
  (is (=
       "aptitude search --disable-columns \"~i\""
       (script/with-script-context [:aptitude]
         (script (~list-installed-packages)))))
  (is (=
       "yum list installed"
       (script/with-script-context [:yum]
         (script (~list-installed-packages))))))

(deftest debconf-test
  (script/with-script-context [:apt]
    (is (=
          "{ debconf-set-selections <<EOF\ndebconf string\nEOF\n}"
          (debconf-set-selections "debconf string")))
    (is (=
          (str "{ debconf-set-selections <<EOF\n"
               "debconf debconf/frontend select noninteractive\n"
               "debconf debconf/frontend seen false\nEOF\n"
               "}")
          (package-manager-non-interactive))))
  (script/with-script-context [:aptitude]
    (is (=
          "{ debconf-set-selections <<EOF\ndebconf string\nEOF\n}"
          (debconf-set-selections "debconf string")))
    (is (=
          (str "{ debconf-set-selections <<EOF\n"
               "debconf debconf/frontend select noninteractive\n"
               "debconf debconf/frontend seen false\nEOF\n"
               "}")
          (package-manager-non-interactive)))))



;;; test hostinfo

(deftest dnsdomainname-test
  (is (=
       "$(dnsdomainname)"
       (script (~dnsdomainname)))))

(deftest dnsdomainname-test
  (is (=
       "$(hostname --fqdn)"
       (script (~hostname :fqdn true)))))

(deftest nameservers-test
  (is (=
       "$(grep nameserver /etc/resolv.conf | cut -f2)"
       (script (~nameservers)))))

;;; test filesystem paths
(defmacro mktest
  [os-family f path]
  `(is (= ~path
          (script/with-script-context [~os-family]
            (fragment
             ~(list f))))))

(deftest etc-default-test
  (mktest :ubuntu etc-default "/etc/default")
  (mktest :debian etc-default "/etc/default")
  (mktest :centos etc-default "/etc/sysconfig")
  (mktest :fedora etc-default "/etc/sysconfig")
  (mktest :os-x etc-default "/etc/defaults"))

(deftest config-root-test
  (mktest :ubuntu config-root "/etc"))

(deftest file-test
  (is (=  "/etc/riemann" (fragment (file (config-root) "riemann"))))
  (let [a "/etc" b "riemann"]
    (is (=  "/etc/riemann" (fragment (file ~a ~b))))))
