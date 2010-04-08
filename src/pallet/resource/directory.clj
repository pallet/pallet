(ns pallet.resource.directory
  "Directory manipulation."
  (:use pallet.script
        pallet.stevedore
        [pallet.utils :only [cmd-join]]
        [pallet.resource :only [defcomponent]]
        [pallet.resource.file :only [chown chgrp chmod]]
        clojure.contrib.logging))

(defscript rmdir [directory & options])
(defimpl rmdir :default [directory & options]
  ("rmdir" ~(map-to-arg-string (first options)) ~directory))

(defscript mkdir [directory & options])
(defimpl mkdir :default [directory & options]
  ("mkdir" ~(map-to-arg-string (first options)) ~directory))

(defn adjust-directory [path opts]
  (cmd-join
   (filter
    (complement nil?)
    [(when (opts :owner)
       (script (chown ~(opts :owner) ~path  ~(select-keys opts [:recursive]))))
     (when (opts :group)
       (script (chgrp ~(opts :group) ~path  ~(select-keys opts [:recursive]))))
     (when (opts :mode)
       (script (chmod ~(opts :mode) ~path  ~(select-keys opts [:recursive]))))])))

(defn make-directory [path opts]
  (cmd-join
   [(script
     (mkdir ~path ~(select-keys opts [:p :v :m])))
    (adjust-directory path opts)]))

(defn directory*
  [path & options]
  (let [opts (if (seq options) (apply hash-map options) {})
        opts (merge {:action :create} opts)]
    (condp = (opts :action)
      :delete
      (script (rm ~path ~{:r (get opts :recursive true)
                          :f (get opts :force true)}))
      :create
      (make-directory path (merge {:p true} opts))
      :touch
      (make-directory path (merge {:p true} opts)))))

(defcomponent directory "Directory management."
  directory* [path & options])
