(ns pallet.resource.user
  "Compatability namespace"
  (:require
   pallet.action.user
   [pallet.common.deprecate :as deprecate]
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib
 user-exists? modify-user create-user remove-user lock-user unlock-user
 user-home current-user group-exists? modify-group create-group remove-group)

(deprecate/forward-fns pallet.action.user user group)
