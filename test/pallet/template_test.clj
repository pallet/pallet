(ns pallet.template-test
  (:use pallet.template)
  (:require
   [pallet.utils :as utils]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.strint :as strint])
  (:use
   clojure.test
   [pallet.actions :only [remote-file]]
   [pallet.api :only [group-spec]]
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.test-utils
    :only [make-node test-session
           with-bash-script-language with-ubuntu-script-template]]))

(use-fixtures
 :once
 with-ubuntu-script-template
 with-bash-script-language
 (logging-threshold-fixture))

(deftest path-components-test
  (is (= ["a/b/c" "d" "e"] (path-components "a/b/c/d.e")))
  (is (= ["a/b/c" "d" nil] (path-components "a/b/c/d"))))

(deftest pathname-test
  (is (= "a/b/c/d.e" (pathname "a/b/c" "d" "e")))
  (is (= "a/b/c/d" (pathname "a/b/c" "d" nil))))

(deftest candidate-templates-test
  (is (= ["a/b/c_t.d" "resources/a/b/c_t.d"
          "a/b/c_ubuntu.d" "resources/a/b/c_ubuntu.d"
          "a/b/c_aptitude.d" "resources/a/b/c_aptitude.d"
          "a/b/c.d" "resources/a/b/c.d"]
           (#'pallet.template/candidate-templates
            "a/b/c.d" "t"
            (test-session
             {:server {:node (make-node :id :group-name :c)}}))))
  (is (= ["c_t.d" "resources/c_t.d" "c_ubuntu.d" "resources/c_ubuntu.d"
          "c_aptitude.d" "resources/c_aptitude.d" "c.d" "resources/c.d"]
         (#'pallet.template/candidate-templates
          "c.d" "t"
          (test-session
           {:server {:node (make-node :id :group-name :c)}})))))


(deftest apply-template-file-test
  (is (= "file=/etc/file
cat > ${file} <<EOF
some content
EOF"
         (#'pallet.template/apply-template-file
          [{:path "/etc/file"} "some content"]))))

(deftest apply-template-file-test
  (is (= "file=/etc/file
cat > ${file} <<EOF
some content
EOF
chmod 0666 ${file}
chgrp grp ${file}
chown user ${file}"
         (#'pallet.template/apply-template-file
          [{:path "/etc/file" :owner "user" :mode "0666"
            :group "grp"}
           "some content"]))))

(deftest apply-template-file-test
  (is (= (first
          (build-actions {}
            (remote-file "/etc/file" :content "some content")
            (remote-file "/etc/file2" :content "some content2")))
         (first
          (build-actions {}
            (apply-templates
             (fn [] {{:path "/etc/file"} "some content"
                     {:path "/etc/file2"} "some content2"})
             nil))))))

(deftest find-template-test
  (let [a (group-spec "a" :image {:os-family :ubuntu})]
    (is (re-find
         #"resources/template/strint"
         (str (find-template
               "template/strint"
               (test-session
                {:group a
                 :server {:node (make-node :id :group-name "a")}})))))
    (is (= "a ~{a}\n"
           (utils/load-resource-url
            (find-template
             "template/strint"
             {:group a
              :server {:node (make-node :id :group-name "a")}}))))))

(deftest interpolate-template-test

  (let [n (group-spec "n" :image {:os-family :ubuntu})
        a 1]
    (is (= "a 1\n"
           (interpolate-template
            "template/strint" (strint/capture-values a)
            {:group n
             :server {:node (make-node :id :group-name "n")}})))))
