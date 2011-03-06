(ns pallet.resource.filesystem-layout
  "Functions to return distribution specific paths.

   These script functions are meant to help build distribution agnostic crates.


   * Links
    - man 7 hier
    - http://www.pathname.com/fhs/
    - http://wiki.apache.org/httpd/DistrosDefaultLayout"
  (:require
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.script :as script-impl]
   pallet.resource.script))

(script/defscript etc-default [])
(script-impl/defimpl etc-default [#{:ubuntu :debian :jeos}] []
  "/etc/default")
(script-impl/defimpl etc-default [#{:centos :rhel :amzn-linux :fedora}] []
  "/etc/sysconfig")
(script-impl/defimpl etc-default [#{:os-x :darwin}] []
  "/etc/defaults")

(script/defscript log-root [])
(script-impl/defimpl log-root :default []
  "/var/log")

(script/defscript pid-root [])
(script-impl/defimpl pid-root :default []
  "/var/run")

(script/defscript config-root [])
(script-impl/defimpl config-root :default []
  "/etc")

(script/defscript etc-hosts [])
(script-impl/defimpl etc-hosts :default []
  "/etc/hosts")

(script/defscript etc-init [])
(script-impl/defimpl etc-init :default []
  "/etc/init.d")

;;; Some of the packagers, like brew, are "add-ons" in the sense that they are
;;; outside of the base system.  These paths refer to locations of packager
;;; installed files.

(script/defscript pkg-etc-default [])
(script-impl/defimpl pkg-etc-default :default [] (etc-default))
(script-impl/defimpl etc-default [:brew] [] "/usr/local/etc/default")

(script/defscript pkg-log-root [])
(script-impl/defimpl pkg-log-root :default [] (log-root))
(script-impl/defimpl pkg-log-root [:brew] [] "/usr/local/var/log")

(script/defscript pkg-pid-root [])
(script-impl/defimpl pkg-pid-root :default [] (pid-root))
(script-impl/defimpl pkg-pid-root [:brew] [] "/usr/local/var/run")

(script/defscript pkg-config-root [])
(script-impl/defimpl pkg-config-root :default [] (config-root))
(script-impl/defimpl pkg-config-root [:brew] [] "/usr/local/etc")

(script/defscript pkg-sbin [])
(script-impl/defimpl pkg-sbin :default [] "/sbin")
(script-impl/defimpl pkg-sbin [:brew] [] "/usr/local/sbin")
