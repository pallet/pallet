(ns pallet.action.environment
  "Set up the system environment."
  (:require
   [pallet.crate.environment]
   [pallet.common.deprecate :as deprecate]))

(deprecate/forward-fns
 "0.7.0"
 pallet.crate.environment
 system-environment)
