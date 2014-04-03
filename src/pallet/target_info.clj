(ns pallet.target-info
  "Functions that return information on the current target."
  (:require
   [pallet.kb :refer [packager-for-os]]
   [pallet.session :as session]
   [pallet.node :as node]
   [pallet.user :refer [user?]]))

;;; These functions take a session so we can override algorithms via the session

(defn admin-user
  "Return the effective admin `user`, from the target or the global admin user."
  [session]
  {:post [(user? %)]}
  (or (node/user (session/target session))
      (session/user session)))
