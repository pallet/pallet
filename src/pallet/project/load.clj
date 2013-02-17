(ns pallet.project.load
  "Namespace for loading pallet project files.  Provides a default set of
  requires that are available to the pallet file."
  (:require
   [pallet.action :refer [with-action-options]]
   [pallet.actions :refer :all :exclude [update-settings assoc-settings]]
   [pallet.api :refer :all]
   [pallet.crate :refer :all :exclude [compute-service]]
   [pallet.crate.automated-admin-user
    :refer [automated-admin-user with-automated-admin-user]]
   [pallet.project.loader :refer [defproject]]))

;;; Anything in here is visible in the pallet.clj project file
