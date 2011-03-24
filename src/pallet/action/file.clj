(ns pallet.action.file
  "File manipulation."
  (:require
   [pallet.action :as action]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.string :as string]))

(defn adjust-file [path options]
  (stevedore/chain-commands*
   (filter
    identity
    [(when (:owner options)
       (stevedore/script (~lib/chown ~(options :owner) ~path)))
     (when (:group options)
       (stevedore/script (~lib/chgrp ~(options :group) ~path)))
     (when (:mode options)
       (stevedore/script (chmod ~(options :mode) ~path)))])))

(defn write-md5-for-file
  "Create a .md5 file for the specified input file"
  [path md5-path]
  (stevedore/script
   ((~lib/md5sum ~path) > ~md5-path)))

(defn touch-file [path {:keys [force] :as options}]
  (stevedore/chain-commands
   (stevedore/script
    (~lib/touch ~path :force ~force))
   (adjust-file path options)))

(action/def-bash-action file
  "File management."
  [request path & {:keys [action owner group mode force]
                   :or {action :create force true}
                   :as options}]
  (case action
    :delete (stevedore/checked-script
             (str "delete file " path)
             (~lib/rm ~path :force ~force))
    :create (stevedore/checked-commands
             (str "file " path)
             (touch-file path options))
    :touch (stevedore/checked-commands
             (str "file " path)
             (touch-file path options))))

(action/def-bash-action symbolic-link
  "Symbolic link management."
  [request from name & {:keys [action owner group mode force]
                        :or {action :create force true}}]
  (case action
    :delete (stevedore/checked-script
             (str "Link %s " name)
             (~lib/rm ~name :force ~force))
    :create (stevedore/checked-script
             (format "Link %s as %s" from name)
             (~lib/ln ~from ~name :force ~force :symbolic ~true))))

(action/def-bash-action fifo
  "FIFO pipe management."
  [request path & {:keys [action] :or {action :create} :as options}]
  (case action
    :delete (stevedore/checked-script
             (str "fifo " path)
             (~lib/rm ~path :force ~force))
    :create (stevedore/checked-commands
             (str "fifo " path)
             (stevedore/script
              (if-not (file-exists? ~path)
                (mkfifo ~path)))
             (adjust-file path options))))

(action/def-bash-action sed
  "Execute sed on a file.  Takes a path and a map for expr to replacement."
  [request path exprs-map & {:keys [seperator no-md5 restriction] :as options}]
  (stevedore/checked-script
   (format "sed file %s" path)
   (~lib/sed-file ~path ~exprs-map ~options)
   ~(when-not no-md5
      (write-md5-for-file path (str path ".md5")))))
