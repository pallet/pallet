(ns pallet.crate-install-test
  (:use
   clojure.test
   pallet.crate-install
   [pallet.build-actions :only [build-actions]]
   [pallet.crate :only [assoc-settings]]))


(deftest install-test
  (is (build-actions {}
        (assoc-settings :f {:install-strategy :packages})
        (install :f nil)))
  (is (build-actions {}
        (assoc-settings :f {:install-strategy :package-source
                            :packages []
                            :package-options {}})
        (install :f nil)))
  (is (build-actions {}
        (assoc-settings :f {:install-strategy :rpm
                            :rpm {:remote-file "http://somewhere.com/"
                                  :name "xx"}})
        (install :f nil)))
  (is (build-actions {}
        (assoc-settings :f {:install-strategy :rpm-repo
                            :rpm {:remote-file "http://somewhere.com/"
                                  :name "xx"}
                            :packages []})
        (install :f nil)))
  (is (build-actions {}
        (assoc-settings :f {:install-strategy :deb
                            :deb {:remote-file "http://somewhere.com/"
                                  :name "xx"}
                            :package-source {:name "xx"}
                            :packages []})
        (install :f nil))))
