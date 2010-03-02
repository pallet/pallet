(ns #^{ :doc "Package management resource."}
  pallet.resource.package
  (:use pallet.script
        pallet.stevedore))

(defscript update-package-list [])
(defscript install-package [name options])
(defscript remove-package [name options])
(defscript purge-package [name options])

(defimpl update-package-list :default [options]
  (aptitude update ~options))

(defimpl install-package :default [package options]
  (aptitude install -y ~options ~package))

(defimpl remove-package :default [package options]
  (aptitude remove ~options ~package))

(defimpl purge-package :default [package options]
  (aptitude purge ~options ~package))

(defn package
  "Package management"
  [package-name & options]
  (let [opts (if options (apply assoc {} options))
        opts (merge opts {:action :install})
        action (get opts :action)]
    (condp = action
      :install
      (script
       (install-package
        ~package-name
        ~(select-keys opts [:base-dir :home :system :create-home :password])))
      :remove
      (if (options :purge)
        (script (purge-package ~package-name))
        (script (remove-package ~package-name)))
      :upgrade
      (script (purge-package ~package-name))
      :update-package-list
      (script (update-package-list))

      (throw (IllegalArgumentException.
              (str action " is not a valid action for package resource"))))))
