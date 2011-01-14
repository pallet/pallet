(ns pallet.resource.filesystem-layout
  "Functions to return distribution specific paths.

   These script functions are meant to help build distribution agnostic crates.


   * Links
    - man 7 hier
    - http://www.pathname.com/fhs/
    - http://wiki.apache.org/httpd/DistrosDefaultLayout"
  (:require
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]))

(script/defscript etc-default [])
(stevedore/defimpl etc-default [#{:ubuntu :debian :jeos}] []
  "/etc/default")
(stevedore/defimpl etc-default [#{:centos :rhel :amzn-linux :fedora}] []
  "/etc/sysconfig")
(stevedore/defimpl etc-default [#{:os-x :darwin}] []
  "/etc/defaults")

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

;;; Some of the packagers, like brew, are "add-ons" in the sense that they are
;;; outside of the base system.  These paths refer to locations of packager
;;; installed files.

(script/defscript pkg-etc-default [])
(stevedore/defimpl pkg-etc-default :default [] (etc-default))
(stevedore/defimpl etc-default [:brew] [] "/usr/local/etc/default")

(script/defscript pkg-log-root [])
(stevedore/defimpl pkg-log-root :default [] (log-root))
(stevedore/defimpl pkg-log-root [:brew] [] "/usr/local/var/log")

(script/defscript pkg-pid-root [])
(stevedore/defimpl pkg-pid-root :default [] (pid-root))
(stevedore/defimpl pkg-pid-root [:brew] [] "/usr/local/var/run")

(script/defscript pkg-config-root [])
(stevedore/defimpl pkg-config-root :default [] (config-root))
(stevedore/defimpl pkg-config-root [:brew] [] "/usr/local/etc")

(script/defscript pkg-sbin [])
(stevedore/defimpl pkg-sbin :default [] "/sbin")
(stevedore/defimpl pkg-sbin [:brew] [] "/usr/local/sbin")
