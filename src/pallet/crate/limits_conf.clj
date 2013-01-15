(ns pallet.crate.limits-conf
  "/etc/hosts file."
  (use [pallet.script :only [defscript defimpl]]
       [pallet.action.exec-script :only [exec-checked-script]])
  (:require
   [pallet.action.file :as file]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :as service]
   [pallet.argument :as argument]
   [pallet.common.deprecate :as deprecate]
   [pallet.compute :as compute]
   [pallet.parameter :as parameter]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.string :as string]))

(defn- format-entry
  [{:keys [domain type item value] :or {type "-"}}]
  (format "%s %s %s %s" domain type item value))

(defn ulimit
  "Declare a host entry"
  [session {:keys [domain type item value] :as entry}]
  (->
   session
   (parameter/update-for-target [:limits-conf] conj entry)))

(defn- format-hosts*
  [entries]
  (string/join "\n" (map format-entry entries)))

(defn- format-hosts
  [session]
  (format-hosts* (parameter/get-for-target session [:limits-conf] nil)))

(defn limits-conf
  "Writes the limit.conf file"
  [session]
  (-> session
      (remote-file/remote-file
       (stevedore/script "/etc/security/limits.conf")
       :owner "root:root"
       :mode 644
       :content (argument/delayed [session] (format-hosts session)))))
