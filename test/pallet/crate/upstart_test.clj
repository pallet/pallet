(ns pallet.crate.upstart-test
  (:use pallet.crate.upstart
        clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (resource/build-resources
       []
       (package)
       (job "abc"
            :script "ls"
            :pre-start-exec "ifup -a"
            :limit {"disk" "100 200"}
            :env "HOME=/home"
            :export ["HOME" "AWAY"]
            :respawn true))))
