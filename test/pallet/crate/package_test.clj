(ns pallet.crate.package-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :as actions]
   [pallet.build-actions :refer [build-plan]]
   [pallet.crate.package :refer [install package package-repository]]
   [pallet.script.lib :refer [package-manager-non-interactive rm]]))

(deftest packages-test
  (is (=
       (build-plan [session {}]
         (actions/packages session ["git" "ruby"]))
       (build-plan [session {}]
         (package session "git")
         (package session "ruby")
         (install session {})))))

(deftest package-repository-test
  (is (=
       (build-plan [session {}]
         (actions/package-source
          session "source1" {:url "http://somewhere/apt" :scopes ["main"]}))
       (build-plan [session {}]
         (package-repository
          session "source1" {:url "http://somewhere/apt" :scopes ["main"]})
         (install session {})))))
