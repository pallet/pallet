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

(script/defscript make-temp-dir [pattern & options])
(stevedore/defimpl make-temp-dir :default [pattern & options]
  @("mktemp" -d
    ~(stevedore/map-to-arg-string (first options))
    ~(str pattern "XXXXX")))

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
   (str "Directory " path)
   (stevedore/script
    (mkdir ~path ~(select-keys opts [:p :v :m])))
   (adjust-directory path opts)))

(defresource directory
  "Directory management."
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
  owner/group/permissions."
  (directories*
   [request paths & options]
   (stevedore/chain-commands*
    (map #(apply directory* request % options) paths))))
