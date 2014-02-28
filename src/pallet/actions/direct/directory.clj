(ns pallet.actions.direct.directory
  "A directory manipulation action, to create and remove directories
   with given ownership and mode."
  (:require
   [pallet.action :refer [implement-action]]
   [pallet.actions.decl :refer [directory]]
   [pallet.actions.impl :refer [checked-script checked-commands]]
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
        (~lib/chmod ~mode ~path)))])))

(defn make-directory
  "Script to create a directory."
  [dir-path & {:keys [path verbose mode recursive] :as opts}]
  (checked-commands
   (str "Directory " dir-path)
   (stevedore/script
    (~lib/mkdir ~dir-path :path ~path :verbose ~verbose :mode ~mode))
   (adjust-directory dir-path opts)))

(defn directory*
  [{:keys [action-options state]}
   dir-path {:keys [action recursive force path mode verbose owner group]
             :or {action :create recursive true force true path true}
             :as options}]
  (case action
    :delete (checked-script
             (str "Delete directory " dir-path)
             (~lib/rm ~dir-path :recursive ~recursive :force ~force))
    :create (make-directory
             dir-path
             :path path :mode mode :verbose verbose
             :owner owner :group group :recursive recursive)
    :touch (make-directory
            dir-path
            :path path :mode mode :verbose verbose
            :owner owner :group group :recursive recursive)))

(implement-action directory :direct
  {:action-type :script :location :target}
  {:language :bash} directory*)
