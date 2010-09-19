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
