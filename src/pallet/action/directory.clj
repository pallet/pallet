(ns pallet.action.directory
  "A directory manipulation action, to create and remove directories
   with given ownership and mode."
  (:require
   [pallet.action :as action]
   [pallet.action.file :as file]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))


(defn adjust-directory
  "Script to set the ownership and mode of a directory."
  [path {:keys [owner group mode recursive] :as opts}]
  (stevedore/chain-commands*
   (filter
    identity
    [(when owner
       (stevedore/script
        (~lib/chown ~owner ~path :recursive ~recursive)))
     (when group
       (stevedore/script
        (~lib/chgrp ~group ~path :recursive ~recursive)))
     (when mode
       (stevedore/script
        (~lib/chmod ~mode ~path :recursive ~recursive)))])))

(defn make-directory
  "Script to create a directory."
  [dir-path & {:keys [path verbose mode] :as opts}]
  (stevedore/checked-commands
   (str "Directory " dir-path)
   (stevedore/script
    (~lib/mkdir ~dir-path :path ~path :verbose ~verbose :mode ~mode))
   (adjust-directory dir-path opts)))

(action/def-bash-action directory
  "Directory management.

   For :create and :touch, all components of path are effected.

   Options are:
    - :action     One of :create, :touch, :delete
    - :recursive  Flag for recursive delete
    - :force      Flag for forced delete
    - :path       flag to create all path elements
    - :owner      set owner
    - :group      set group
    - :mode       set mode"

  [session dir-path & {:keys [action recursive force path mode verbose owner
                              group]
                       :or {action :create recursive true force true path true}
                       :as options}]
  (case action
    :delete (stevedore/checked-script
             (str "Delete directory " dir-path)
             (~lib/rm ~dir-path :recursive ~recursive :force ~force))
    :create (make-directory
             dir-path
             :path path :mode mode :verbose verbose
             :owner owner :group group)
    :touch (make-directory
            dir-path
            :path path :mode mode :verbose verbose
            :owner owner :group group)))

(action/def-bash-action directories
  "Directory management of multiple directories with the same
   owner/group/permissions.

   `options` are as for `directory` and are applied to each directory in
   `paths`"
  [session paths & options]
  (stevedore/chain-commands*
   (map #(apply (action/action-fn directory) session % options) paths)))
