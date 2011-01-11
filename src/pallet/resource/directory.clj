(ns pallet.resource.directory
  "A directory manipulation resource, to create and remove directories
   with given ownership and mode."
  (:require
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.script :as script])
  (:use
   [pallet.resource :only [defresource]]
   [pallet.resource.file :only [chown chgrp chmod]]
   clojure.contrib.logging))

(script/defscript rmdir
  "Remove the specified directory"
  [directory & options])

(stevedore/defimpl rmdir :default [directory & options]
  ("rmdir" ~(stevedore/map-to-arg-string (first options)) ~directory))

(script/defscript mkdir
  "Create the specified directory"
  [directory & options])
(stevedore/defimpl mkdir :default [directory & options]
  ("mkdir" ~(stevedore/map-to-arg-string (first options)) ~directory))

(script/defscript make-temp-dir
  "Create a temporary directory"
  [pattern & options])
(stevedore/defimpl make-temp-dir :default [pattern & options]
  @("mktemp" -d
    ~(stevedore/map-to-arg-string (first options))
    ~(str pattern "XXXXX")))

(defn adjust-directory
  "Script to set the ownership and mode of a directory."
  [path opts]
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

(defn make-directory
  "Script to create a directory."
  [path opts]
  (stevedore/checked-commands
   (str "Directory " path)
   (stevedore/script
    (mkdir ~path ~(select-keys opts [:p :v :m])))
   (adjust-directory path opts)))

(defresource directory
  "Directory management.

   For :create and :touch, all components of path are effected.

   Options are:
    - :action     One of :create, :touch, :delete
    - :recursive  Flag for recursive delete
    - :force      Flag for forced delete"
  (directory*
   [request path & {:keys [action] :or {action :create} :as options}]
   (case action
     :delete (stevedore/checked-script
              (str "Delete directory " path)
              (rm ~path ~{:r (get options :recursive true)
                          :f (get options :force true)}))
     :create (make-directory path (merge {:p true} options))
     :touch (make-directory path (merge {:p true} options)))))

(defresource directories
  "Directory management of multiple directories with the same
   owner/group/permissions.

   `options` are as for `directory` and are applied to each directory in
   `paths`"
  (directories*
   [request paths & options]
   (stevedore/chain-commands*
    (map #(apply directory* request % options) paths))))
