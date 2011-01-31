(ns pallet.resource.filesystem-layout-test
  (:use clojure.test)
  (:require
   [pallet.resource.filesystem-layout :as filesystem-layout]
   [pallet.stevedore :as stevedore]
   [pallet.script :as script]
   [pallet.test-utils :as test-utils]))

(defmacro mktest
  [os-family f path]
  `(is (= ~path
          (script/with-template [~os-family]
            (stevedore/script
             (~(symbol "pallet.resource.filesystem-layout" (name f))))))))

(deftest etc-default-test
  (mktest :ubuntu etc-default "/etc/default")
  (mktest :debian etc-default "/etc/default")
  (mktest :centos etc-default "/etc/sysconfig")
  (mktest :fedora etc-default "/etc/sysconfig")
  (mktest :os-x etc-default "/etc/defaults"))
