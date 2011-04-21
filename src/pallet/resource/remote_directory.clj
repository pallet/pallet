(ns pallet.resource.remote-directory
  "Compatability namespace"
  (:require
   pallet.action.remote-directory
   [pallet.common.deprecate :as deprecate]))

(deprecate/forward-fns pallet.action.remote-directory remote-directory)
