(ns pallet.bootstrap-test
 (:require pallet.compat)
  (:use [pallet.bootstrap] :reload-all)
  (:use clojure.test
        pallet.test-utils))

(pallet.compat/require-contrib)

(def expected-fragment "apt-get update\n")

(defn test-template [s]
  {:authorize-public-key nil
   :bootstrap-script (fn [tag template] s)})

(with-private-vars [pallet.bootstrap [bootstrap-fragment-paths
                                      bootstrap-merge
                                      template-os-family]]
  (deftest bootstrap-fragment-paths-test
    (is (= [ (pallet.compat/file "bootstrap" "frag" "tag")
             (pallet.compat/file "bootstrap" "frag" "os-family")
             (pallet.compat/file "bootstrap" "frag" "default")]
           (bootstrap-fragment-paths :frag "tag" :os-family)))
    (is (= [ (pallet.compat/file "bootstrap" "frag" "tag")
             (pallet.compat/file "bootstrap" "frag" "os-family")
             (pallet.compat/file "bootstrap" "frag" "default")]
           (bootstrap-fragment-paths :frag :tag :os-family)))
    (is (= [ (pallet.compat/file "bootstrap" "frag" "tag")
             (pallet.compat/file "bootstrap" "frag" "default")]
           (bootstrap-fragment-paths :frag "tag" nil))))

  (deftest bootstrap-merge-test
    (is (= {:authorize-public-key nil :bootstrap-script nil}
           (bootstrap-merge {:authorize-public-key nil :bootstrap-script nil}
                            {:authorize-public-key nil :bootstrap-script nil})))
    (is (= {:authorize-public-key :a :bootstrap-script nil}
           (bootstrap-merge {:authorize-public-key :a :bootstrap-script nil}
                            {:authorize-public-key nil :bootstrap-script nil})))
    (is (= {:authorize-public-key :a :bootstrap-script nil}
           (bootstrap-merge {:authorize-public-key :a :bootstrap-script nil}
                            {:authorize-public-key :b :bootstrap-script nil})))
    (is (= {:authorize-public-key :b :bootstrap-script nil}
           (bootstrap-merge {:authorize-public-key nil :bootstrap-script nil}
                            {:authorize-public-key :b :bootstrap-script nil}))))

  (deftest bootstrap-fragment-test
    (is (= :ubuntu
           (template-os-family [:ubuntu])))

    (is (= expected-fragment
           (bootstrap-fragment 'update-pkg-mgr "tag" [:ubuntu])))
    (is (= expected-fragment
           (bootstrap-fragment :update-pkg-mgr "tag" [:ubuntu])))
    (is (= expected-fragment
           ((:bootstrap-script (bootstrap-template :update-pkg-mgr)) "tag" [:ubuntu])))))

(deftest bootstrap-with-test
  (is (= {:authorize-public-key nil :bootstrap-script []}
           (bootstrap-with {:authorize-public-key nil :bootstrap-script nil}
                           {:authorize-public-key nil :bootstrap-script nil})))
    (is (= {:authorize-public-key :a :bootstrap-script []}
           (bootstrap-with {:authorize-public-key :a :bootstrap-script nil}
                           {:authorize-public-key nil :bootstrap-script nil})))
    (is (= {:authorize-public-key :a :bootstrap-script []}
           (bootstrap-with {:authorize-public-key :a :bootstrap-script nil}
                           {:authorize-public-key :b :bootstrap-script nil})))
    (is (= {:authorize-public-key :b :bootstrap-script []}
           (bootstrap-with {:authorize-public-key nil :bootstrap-script nil}
                           {:authorize-public-key :b :bootstrap-script nil}))))


(deftest bootstrap-with-update-pkg-mgr-test
  (is (= expected-fragment
         ((first (:bootstrap-script (bootstrap-with (bootstrap-template :update-pkg-mgr))))
          "tag" [:ubuntu])))
  (is (= "first"
       ((first (:bootstrap-script (bootstrap-with (test-template "first")
                                                    (test-template "second"))))
          "tag" [:ubuntu])))
  (is (= "second"
       ((second (:bootstrap-script (bootstrap-with (test-template "first")
                                                    (test-template "second"))))
          "tag" [:ubuntu]))))
