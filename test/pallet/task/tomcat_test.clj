(ns pallet.task.tomcat-test
  (:use pallet.task.tomcat)
  (:require [pallet.core :as core])
  (:use
   clojure.test
   pallet.test-utils))


(deftest war-file-name-test
  (is (= "a-b.war"
         (#'pallet.task.tomcat/war-file-name {:name "a" :version "b"}))))
