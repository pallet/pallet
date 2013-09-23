(ns pallet.actions.direct.file
  "File manipulation."
  (:require
   [pallet.action :refer [implement-action]]
   [pallet.actions.decl :refer [checked-script checked-commands]]
   [pallet.actions :refer [fifo file sed symbolic-link]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(defn adjust-file [path options]
  (stevedore/chain-commands*
   (filter
    identity
    [(when (:owner options)
       (stevedore/script (~lib/chown ~(options :owner) ~path)))
     (when (:group options)
       (stevedore/script (~lib/chgrp ~(options :group) ~path)))
     (when (:mode options)
       (stevedore/script ("chmod" ~(options :mode) ~path)))])))

(defn write-md5-for-file
  "Create a .md5 file for the specified input file"
  [path md5-path]
  (stevedore/script
   "("
   (chain-and
    (var cp @(~lib/canonical-path ~path))
    ("cd" @(~lib/dirname @cp))
    ((~lib/md5sum @(~lib/basename @cp))))
   ")" > ~md5-path))


(defn touch-file [path {:keys [force] :as options}]
  (stevedore/chain-commands
   (stevedore/script
    (~lib/touch ~path :force ~force))
   (adjust-file path options)))

(implement-action file :direct
  {:action-type :script :location :target}
  [session path & {:keys [action owner group mode force]
                   :or {action :create force true}
                   :as options}]
  [[{:language :bash}
    (case action
      :delete (checked-script
               (str "delete file " path)
               (~lib/rm ~path :force ~force))
      :create (checked-commands
               (str "file " path)
               (touch-file path options))
      :touch (checked-commands
              (str "file " path)
              (touch-file path options)))]
   session])

(implement-action symbolic-link :direct
  {:action-type :script :location :target}
  [session from name & {:keys [action owner group mode force]
                        :or {action :create force true}}]
  [[{:language :bash}
    (case action
      :delete (checked-script
               (str "Link %s " name)
               (~lib/rm ~name :force ~force))
      :create (checked-script
               (format "Link %s as %s" from name)
               (~lib/ln ~from ~name :force ~force :symbolic ~true)))]
   session])

(implement-action fifo :direct
  {:action-type :script :location :target}
  [session path & {:keys [action owner group mode force]
                   :or {action :create} :as options}]
  [[{:language :bash}
    (case action
      :delete (checked-script
               (str "fifo " path)
               (~lib/rm ~path :force ~force))
      :create (checked-commands
               (str "fifo " path)
               (stevedore/script
                (if-not (file-exists? ~path)
                  ("mkfifo" ~path)))
               (adjust-file path options)))]
   session])

(implement-action sed :direct
  {:action-type :script :location :target}
  [session path exprs-map
   & {:keys [seperator no-md5 restriction] :as options}]
  [[{:language :bash}
    (checked-script
     (format "sed file %s" path)
     (~lib/sed-file ~path ~exprs-map ~options)
     ~(when-not no-md5
        (write-md5-for-file path (str path ".md5"))))]
   session])
