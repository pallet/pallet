(ns pallet.template-test
  (:use pallet.template)
  (:require
   [pallet.core :as core]
   [pallet.utils :as utils]
   [pallet.strint :as strint]
   [pallet.target :as target]
   [pallet.test-utils :as test-utils])
  (:use
   clojure.test
   pallet.test-utils))

(use-fixtures
 :once
 with-ubuntu-script-template
 test-utils/with-bash-script-language)

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
            {:server {:image {:os-family :ubuntu}
                      :group-name :c
                      :packager :aptitude}})))
  (is (= ["c_t.d" "resources/c_t.d" "c_ubuntu.d" "resources/c_ubuntu.d"
          "c_aptitude.d" "resources/c_aptitude.d" "c.d" "resources/c.d"]
         (#'pallet.template/candidate-templates
          "c.d" "t"
          {:server {:image {:os-family :ubuntu}
                    :group-name :c
                    :packager :aptitude}}))))

(with-private-vars [pallet.template [apply-template-file]]
  (deftest apply-template-file-test
    (is (= "file=/etc/file
cat > ${file} <<EOF
some content
EOF"
           (apply-template-file [{:path "/etc/file"} "some content"]))))

  (deftest apply-template-file-test
    (is (= "file=/etc/file
cat > ${file} <<EOF
some content
EOF
chmod 0666 ${file}
chgrp grp ${file}
chown user ${file}"
           (apply-template-file [{:path "/etc/file" :owner "user" :mode "0666"
                                  :group "grp"}
                                 "some content"])))))

(deftest apply-template-file-test
    (is (= "file=/etc/file
cat > ${file} <<EOF
some content
EOF
file=/etc/file2
cat > ${file} <<EOF
some content2
EOF
"
           (apply-templates (fn [] {{:path "/etc/file"} "some content"
                                    {:path "/etc/file2"} "some content2"})
                            nil))))

(deftest find-template-test
  (let [a (core/group-spec "a" :image {:os-family :ubuntu})]
    (is (re-find
         #"resources/template/strint"
         (str (find-template "template/strint" {:server a}))))
    (is (= "a ~{a}\n"
           (utils/load-resource-url
            (find-template "template/strint" {:server a}))))))

(deftest interpolate-template-test

  (let [n(core/group-spec "n" :image {:os-family :ubuntu})
        a 1]
    (is (= "a 1\n"
           (interpolate-template
            "template/strint" (strint/capture-values a) {:server n})))))
