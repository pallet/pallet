(ns pallet.target-info
  "Functions that return information on the current target."
  (:require
   [pallet.compute :refer [packager-for-os]]
   [pallet.session :as session]
   [pallet.target :as target]
   [pallet.user :refer [user?]]))

;;; These functions take a session so we can override algorithms via the session

(defn admin-user
  "Return the effective admin `user`, from the target or the global admin user."
  [session]
  {:post [(user? %)]}
  (or (target/user (session/target session))
      (session/user session)))

(defn packager
  [session]
  (let [target (session/target session)]
    (or (target/packager target)
        (if-let [os-family (target/os-family target)]
          (packager-for-os os-family (target/os-version target))))))
