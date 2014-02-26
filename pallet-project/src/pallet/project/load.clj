(ns pallet.project.load
  "Namespace for loading pallet project files.  Provides a default set of
  requires that are available to the pallet file.")

;;; Anything in here is visible in the pallet.clj project file.
;;; The explicit require is to prevent slamhound removing it.
(require
 '[pallet.action-options :refer [with-action-options]]
 '[pallet.actions :refer :all :exclude [update-settings assoc-settings]]
 '[pallet.api :refer :all]
 '[pallet.crate :refer :all :exclude [compute-service]]
 '[pallet.crate.automated-admin-user
   :refer [automated-admin-user with-automated-admin-user]]
 '[pallet.project.loader :refer [defproject]])
