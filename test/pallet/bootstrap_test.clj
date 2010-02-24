(ns pallet.bootstrap-test
  (:use [pallet.bootstrap] :reload-all)
  (:use clojure.test
        pallet.test-utils
        [clojure.contrib.java-utils :only [file]]))

(defonce expected-fragment "apt-get update\napt-get upgrade -y\n")

(defn test-template [s]
  {:authorize-public-key nil
   :bootstrap-script (fn [tag template] s)})

(with-private-vars [pallet.bootstrap [bootstrap-fragment-paths
                                      bootstrap-merge
                                      resource-readable?
                                      template-os-family]]
  (deftest bootstrap-fragment-paths-test
    (is (= [ (file "bootstrap" "frag" "tag")
             (file "bootstrap" "frag" "os-family")
             (file "bootstrap" "frag" "default")]
           (bootstrap-fragment-paths :frag "tag" :os-family)))
    (is (= [ (file "bootstrap" "frag" "tag")
             (file "bootstrap" "frag" "os-family")
             (file "bootstrap" "frag" "default")]
           (bootstrap-fragment-paths :frag :tag :os-family)))
    (is (= [ (file "bootstrap" "frag" "tag")
             (file "bootstrap" "frag" "default")]
           (bootstrap-fragment-paths :frag "tag" nil))))

  (deftest bootstrap-merge-test
    (is (= {:authorize-public-key nil :bootstrap-script []}
           (bootstrap-merge {:authorize-public-key nil :bootstrap-script nil}
                            {:authorize-public-key nil :bootstrap-script nil})))
    (is (= {:authorize-public-key :a :bootstrap-script []}
           (bootstrap-merge {:authorize-public-key :a :bootstrap-script nil}
                            {:authorize-public-key nil :bootstrap-script nil})))
    (is (= {:authorize-public-key :a :bootstrap-script []}
           (bootstrap-merge {:authorize-public-key :a :bootstrap-script nil}
                            {:authorize-public-key :b :bootstrap-script nil})))
    (is (= {:authorize-public-key :b :bootstrap-script []}
           (bootstrap-merge {:authorize-public-key nil :bootstrap-script nil}
                            {:authorize-public-key :b :bootstrap-script nil}))))

  (deftest bootstrap-fragment-test
    (is (= :ubuntu
           (template-os-family [:ubuntu])))

    (is (= "bootstrap/update-pkg-mgr/ubuntu"
           (.getPath
            (first
             (filter resource-readable?
                     (bootstrap-fragment-paths :update-pkg-mgr "tag" :ubuntu))))))

    (is (= expected-fragment
           (bootstrap-fragment 'update-pkg-mgr "tag" [:ubuntu])))
    (is (= expected-fragment
           (bootstrap-fragment :update-pkg-mgr "tag" [:ubuntu])))
    (is (= expected-fragment
           ((:bootstrap-script (bootstrap-template :update-pkg-mgr)) "tag" [:ubuntu]))))  )

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
