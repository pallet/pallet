(ns pallet.actions.direct.directory
  "A directory manipulation action, to create and remove directories
   with given ownership and mode."
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.action :only [implement-action action-fn]]
   [pallet.actions :only [directories directory]]))

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
  (action-plan/checked-commands
   (str "Directory " dir-path)
   (stevedore/script
    (~lib/mkdir ~dir-path :path ~path :verbose ~verbose :mode ~mode))
   (adjust-directory dir-path opts)))

(implement-action directory :direct
  {:action-type :script :location :target}
  [session dir-path & {:keys [action recursive force path mode verbose owner
                              group]
                       :or {action :create recursive true force true path true}
                       :as options}]
  [[{:language :bash}
    (case action
      :delete (action-plan/checked-script
               (str "Delete directory " dir-path)
               (~lib/rm ~dir-path :recursive ~recursive :force ~force))
      :create (make-directory
               dir-path
               :path path :mode mode :verbose verbose
               :owner owner :group group :recursive recursive)
      :touch (make-directory
              dir-path
              :path path :mode mode :verbose verbose
              :owner owner :group group :recursive recursive))]
   session])

(implement-action directories :direct
  {:action-type :script :location :target}
  [session paths & options]
  [[{:language :bash}
    (stevedore/chain-commands*
      (map
       #(-> (apply (action-fn directory :direct) session % options)
            first second)
       paths))]
   session])
