(ns #^{ :doc "Package management resource."}
  pallet.resource.package
  (:require [clojure.contrib.str-utils2 :as string])
  (:use pallet.script
        pallet.stevedore
        [pallet.resource :only [defresource]]
        [clojure.contrib.logging]))

(defscript update-package-list [& options])
(defscript install-package [name & options])
(defscript remove-package [name & options])
(defscript purge-package [name & options])

(defimpl update-package-list :default [& options]
  (aptitude update ~(option-args options)))

(defimpl install-package :default [package & options]
  (aptitude install -y ~(option-args options) ~package))

(defimpl remove-package :default [package & options]
  (aptitude remove ~(option-args options) ~package))

(defimpl purge-package :default [package & options]
  (aptitude purge ~(option-args options) ~package))

(defn apply-package
  "Package management"
  [package-name & options]
  (let [opts (if options (apply assoc {} options))
        opts (merge opts {:action :install})
        action (get opts :action)]
    (condp = action
      :install
      (script
       (apply install-package
        ~package-name
        ~(apply concat (select-keys opts [:y :force]))))
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


(def package-args (atom []))

(defn- apply-packages [package-args]
  (info "apply-packages")
  (string/join \newline (map #(apply apply-package %) package-args)))


(defresource package "Package management.
" package-args apply-packages [packagename & options])
