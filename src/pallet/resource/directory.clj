(ns pallet.resource.directory
  "Directory manipulation."
  (:require
   [pallet.utils :as utils])
  (:use pallet.script
        pallet.stevedore
        [pallet.resource :only [defresource]]
        [pallet.resource.file :only [chown chgrp chmod]]
        clojure.contrib.logging))

(defscript rmdir [directory & options])
(defimpl rmdir :default [directory & options]
  ("rmdir" ~(map-to-arg-string (first options)) ~directory))

(defscript mkdir [directory & options])
(defimpl mkdir :default [directory & options]
  ("mkdir" ~(map-to-arg-string (first options)) ~directory))

(defn adjust-directory [path opts]
  (utils/cmd-chain
   (filter
    (complement nil?)
    [(when (opts :owner)
       (script (chown ~(opts :owner) ~path  ~(select-keys opts [:recursive]))))
     (when (opts :group)
       (script (chgrp ~(opts :group) ~path  ~(select-keys opts [:recursive]))))
     (when (opts :mode)
       (script (chmod ~(opts :mode) ~path  ~(select-keys opts [:recursive]))))])))

(defn make-directory [path opts]
  (utils/cmd-join-checked
   (str "directory " path)
   [(script
     (mkdir ~path ~(select-keys opts [:p :v :m])))
    (adjust-directory path opts)]))

(defn directory*
  [path & options]
  (let [opts (apply hash-map options)
        opts (merge {:action :create} opts)]
    (condp = (opts :action)
      :delete
      (checked-script
       (str "directory " path)
       (rm ~path ~{:r (get opts :recursive true)
                   :f (get opts :force true)}))
      :create
      (make-directory path (merge {:p true} opts))
      :touch
      (make-directory path (merge {:p true} opts)))))

(defresource directory "Directory management."
  directory* [path & options])
