(ns pallet.resource.package-test
  (:use [pallet.resource.package] :reload-all)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils))

(deftest update-package-list-test
  (is (= "aptitude update "
         (script (update-package-list)))))

(deftest install-package-test
  (is (= "aptitude install -y  java"
         (script (install-package "java")))))


(deftest test-install-example
  (is (= "aptitude install -y  java\naptitude install -y  rubygems"
         ((pallet.resource/build-resources
           (package "java" :action :install)
           (package "rubygems" :action :install))))))
