(ns pallet.resource.directory
  "Directory manipulation."
  (:require
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.script :as script])
  (:use
   [pallet.resource :only [defresource]]
   [pallet.resource.file :only [chown chgrp chmod]]
   clojure.contrib.logging))

(script/defscript rmdir [directory & options])
(stevedore/defimpl rmdir :default [directory & options]
  ("rmdir" ~(stevedore/map-to-arg-string (first options)) ~directory))

(script/defscript mkdir [directory & options])
(stevedore/defimpl mkdir :default [directory & options]
  ("mkdir" ~(stevedore/map-to-arg-string (first options)) ~directory))

(defn adjust-directory [path opts]
  (stevedore/chain-commands*
   (filter
    (complement nil?)
    [(when (opts :owner)
       (stevedore/script
        (chown ~(opts :owner) ~path  ~(select-keys opts [:recursive]))))
     (when (opts :group)
       (stevedore/script
        (chgrp ~(opts :group) ~path  ~(select-keys opts [:recursive]))))
     (when (opts :mode)
       (stevedore/script
        (chmod ~(opts :mode) ~path  ~(select-keys opts [:recursive]))))])))

(defn make-directory [path opts]
  (stevedore/checked-commands
   (str "directory " path)
   (stevedore/script
    (mkdir ~path ~(select-keys opts [:p :v :m])))
   (adjust-directory path opts)))

(defn directory*
  [path & options]
  (let [opts (apply hash-map options)
        opts (merge {:action :create} opts)]
    (condp = (opts :action)
      :delete
      (stevedore/checked-script
       (str "directory " path)
       (rm ~path ~{:r (get opts :recursive true)
                   :f (get opts :force true)}))
      :create
      (make-directory path (merge {:p true} opts))
      :touch
      (make-directory path (merge {:p true} opts)))))

(defresource directory "Directory management."
  directory* [path & options])

(defn directories*
  "Create directories and set permisions"
  [paths & options]
  (stevedore/chain-commands*
   (map #(apply directory* % options) paths)))

(defresource directories "Directory management of multiple directories."
  directories* [paths & options])

