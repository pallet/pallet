(ns pallet.actions.crate.package-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-checked-script]]
   [pallet.actions.crate.package :refer [packages package-repository]]
   [pallet.actions.decl :refer [remote-file-action]]
   [pallet.build-actions :refer [build-actions]]
   [pallet.script.lib :refer [package-manager-non-interactive rm]]))

(deftest packages-test
  (is (script-no-comment=
       (first
        (build-actions
            {}
          (exec-checked-script
           "Packages"
           (package-manager-non-interactive)
           (chain-and
            (defn enableStart [] (rm "/usr/sbin/policy-rc.d"))
            "apt-get -q -y install git+ ruby+"
            ("dpkg" "--get-selections")))))
       (first
        (build-actions {:server {:tag :n :image {:os-family :ubuntu}}}
          (packages [{:package "git"}{:package "ruby"}]))))))

(deftest package-repository-test
  (is (script-no-comment=
       (first
        (build-actions {}
          (remote-file-action
           "/etc/apt/sources.list.d/source1.list"
           {:content "deb http://somewhere/apt $(lsb_release -c -s) main\n"
            :flag-on-changed "packagesourcechanged"})))
       (first
        (build-actions {:server {:tag :n :image {:os-family :ubuntu}}}
          (package-repository
           {:repository "source1"
            :url "http://somewhere/apt"
            :scopes ["main"]}))))))
