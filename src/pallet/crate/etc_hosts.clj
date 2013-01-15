(ns pallet.crate.etc-hosts
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
  [entry]
  (format "%s %s"  (key entry) (name (val entry))))

(defn host
  "Declare a host entry"
  [session address names]
  (->
   session
   (parameter/update-for-target [:hosts] merge {address names})))

(defn hosts-for-group
  "Declare host entries for all nodes of a group"
  [session group-name & {:keys [private-ip]}]
  (let [ip (if private-ip compute/private-ip compute/primary-ip)]
    (->
     session
     (parameter/update-for-target
      [:hosts]
      merge
      (into
       {}
       (map #(vector (ip %) (compute/hostname %))
            (session/nodes-in-group session group-name)))))))

(defn hosts-for-tag
  "Declare host entries for all nodes of a tag"
  {:deprecated "0.5.0"}
  [session tag & {:keys [private-ip] :as opts}]
  (deprecate/deprecated
   (deprecate/rename
    'pallet.crate.etc-hosts-for-tag 'pallet.crate.etc-hosts/hosts-for-group))
  (apply hosts-for-group session tag (apply concat opts)))

(def ^{:private true} localhost
  {"127.0.0.1" "localhost localhost.localdomain"})

(defn- format-hosts*
  [entries]
  (string/join "\n" (map format-entry entries)))

(defn- format-hosts
  [session]
  (format-hosts*
   (conj localhost (parameter/get-for-target session [:hosts] nil))))

(defn hosts
  "Writes the hosts files"
  [session]
  (-> session
      (remote-file/remote-file
       (stevedore/script (~lib/etc-hosts))
       :owner "root:root"
       :mode 644
       :content (argument/delayed [session] (format-hosts session)))))


;;; set the node's host name.
(compute/defmulti-os hostname [session name])

(defmethod hostname :linux [session name]
  (-> session
      ;; change the hostname now
      (pallet.action.exec-script/exec-script ("hostname " ~name))
      ;; make sure this change will survive reboots
      (remote-file/remote-file
       "/etc/hostname"
       :owner "root" :group "root" :mode "0644"
       :content name )))

(defmethod hostname :rh-base [session name]
  (-> session
      ;; change the hostname now
      (pallet.action.exec-script/exec-script ("hostname " ~name))
      ;; make sure this change will survive reboots
      (file/sed "/etc/sysconfig/network"
                {"HOSTNAME=.*" (str "HOSTNAME=" name)})))

