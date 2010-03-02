(ns pallet.crate.automated-admin-user-test
  (:use [pallet.crate.automated-admin-user] :reload-all)
  (:require [pallet.template :only [apply-templates]]
            [pallet.resource :only [build-resources]])
  (:use clojure.test
        pallet.test-utils
        [clojure.contrib.java-utils :only [file]]))

(with-private-vars [pallet.crate.authorize-key
                    []])

(deftest automated-admin-user-test
  (is (automated-admin-user "user")))
