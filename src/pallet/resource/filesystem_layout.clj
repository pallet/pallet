(ns pallet.resource.filesystem-layout
  "Functions to return distribution specific paths"
  (:require
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]))

(script/defscript etc-default [])
(stevedore/defimpl etc-default [#{:ubuntu :debian :jeos :fedora}] []
  "/etc/default")
(stevedore/defimpl etc-default [#{:centos :rhel :amzn-linux}] []
  "/etc/sysconfig")

(script/defscript log-root [])
(stevedore/defimpl log-root :default []
  "/var/log")

(script/defscript pid-root [])
(stevedore/defimpl pid-root :default []
  "/var/run")

(script/defscript config-root [])
(stevedore/defimpl config-root :default []
  "/etc")

(script/defscript etc-hosts [])
(stevedore/defimpl etc-hosts :default []
  "/etc/hosts")

(script/defscript etc-init [])
(stevedore/defimpl etc-init :default []
  "/etc/init.d")
