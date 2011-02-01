(ns pallet.resource.directory
  "A directory manipulation resource, to create and remove directories
   with given ownership and mode."
  (:require
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.script :as script-impl]
   [pallet.resource.file :as file]
   [pallet.script :as script])
  (:use
   [pallet.resource :only [defresource]]
   [pallet.resource.file :only [chown chgrp chmod rm]]
   clojure.contrib.logging))

(script/defscript rmdir
  "Remove the specified directory"
  [directory & {:as options}])

(script-impl/defimpl rmdir :default [directory & {:as options}]
  ("rmdir" ~(stevedore/map-to-arg-string options) ~directory))

(script/defscript mkdir
  "Create the specified directory"
  [directory & {:keys [path verbose mode]}])
(script-impl/defimpl mkdir :default
  [directory & {:keys [path verbose mode] :as options}]
  ("mkdir"
   ~(stevedore/map-to-arg-string {:m mode :p path :v verbose})
   ~directory))

(script/defscript make-temp-dir
  "Create a temporary directory"
  [pattern & {:as options}])
(script-impl/defimpl make-temp-dir :default [pattern & {:as options}]
  @("mktemp" -d
    ~(stevedore/map-to-arg-string options)
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
  [dir-path & {:keys [path verbose mode] :as opts}]
  (stevedore/checked-commands
   (str "Directory " dir-path)
   (stevedore/script
    (mkdir ~dir-path :path ~path :verbose ~verbose :mode ~mode))
   (adjust-directory dir-path opts)))

(defresource directory
  "Directory management.

   For :create and :touch, all components of path are effected.

   Options are:
    - :action     One of :create, :touch, :delete
    - :recursive  Flag for recursive delete
    - :force      Flag for forced delete"
  (directory*
   [request dir-path & {:keys [action recursive force path mode verbose]
                        :or {action :create recursive true force true path true}
                        :as options}]
   (case action
     :delete (stevedore/checked-script
              (str "Delete directory " dir-path)
              (file/rm ~dir-path :recursive ~recursive :force ~force))
     :create (make-directory dir-path :path path :mode mode :verbose verbose)
     :touch (make-directory dir-path :path path :mode mode :verbose verbose))))

(defresource directories
  "Directory management of multiple directories with the same
   owner/group/permissions.

   `options` are as for `directory` and are applied to each directory in
   `paths`"
  (directories*
   [request paths & options]
   (stevedore/chain-commands*
    (map #(apply directory* request % options) paths))))
