(ns pallet.crate.squeak-test
  (:use pallet.crate.squeak
        clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest smalltalk-test
  (is (= "obj new.\n"
         (smalltalk "obj" :new)))
  (is (= "obj new m: 1.\n"
         (smalltalk "obj new" {:m 1})))
  (is (= "obj new m: 1;\nn: 2.\n"
         (smalltalk "obj new" {:m 1} {:n 2})))
  (is (= "obj new n: 2.\n"
         (smalltalk "obj new" nil {:n 2})))
  (is (= "Installer ss project: 'proj';
install: 'p1';
install: 'p2'.\n"
         (smalltalk "Installer ss" {:project "'proj'"}
                    {:install "'p1'"} {:install "'p2'"}))))

(deftest pharo-package-test
  (is (= "Installer ss project: 'proj';
install: 'p1';
install: 'p2'.\n"
         (pharo-package "ss" "proj" "p1" "p2"))))
