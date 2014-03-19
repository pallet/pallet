(ns pallet.crate.package.debian-backports-test
  (:require
   [clojure.test :refer :all]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :refer [package package-source]]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate.package.debian-backports :refer [add-debian-backports]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils :refer [with-no-source-line-comments]]))

(use-fixtures :once
  (logging-threshold-fixture)
  with-no-source-line-comments)

(deftest debian-backports-test
  (is
   (=
    (first
     (build-actions
         {:server {:image {:os-family :debian}}
          :phase-context "add-debian-backports"}
       (with-action-options {:always-before ::backports}
         (package "lsb-release"))
       (package-source
        "debian-backports"
        :aptitude {:url (str "http://ftp.us.debian.org/debian"
                             (stevedore/fragment
                              @(if (= (lib/os-version-name) "squeeze")
                                 ("echo" -n "-backports"))))
                   :release (str
                             (stevedore/fragment
                              (~lib/os-version-name)) "-backports")
                   :scopes ["main"]})))
    (first
     (build-actions
         {:server {:image {:os-family :debian}}}
       (add-debian-backports))))))
