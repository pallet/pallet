(ns pallet.crate.etc-hosts
  "/etc/hosts file."
 (:require
   [clojure.string :as string]
   [pallet.node :as node]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils])
 (:use
  [pallet.actions :only [remote-file]]
  [pallet.crate
   :only [def-plan-fn defplan get-settings update-settings nodes-in-group]]))

(defn- format-entry
  [entry]
  (format "%s %s"  (key entry) (name (val entry))))

(def-plan-fn host
  "Declare a host entry"
  [address names]
  (update-settings :hosts merge {address names}))

(def-plan-fn hosts-for-group
  "Declare host entries for all nodes of a group"
  [group-name & {:keys [private-ip]}]
  [ip (m-result (if private-ip node/private-ip node/primary-ip))
   group-nodes (nodes-in-group group-name)]
  (update-settings
   :hosts merge (into {} (map #(vector (ip %) (node/hostname %)) group-nodes))))

(def ^{:private true} localhost
  {"127.0.0.1" "localhost localhost.localdomain"})

(defn- format-hosts*
  [entries]
  (string/join "\n" (map format-entry entries)))

(defplan format-hosts
  [settings (get-settings :hosts)]
  (format-hosts* (merge settings localhost)))

(defplan hosts
  "Writes the hosts files"
  [content format-hosts]
  (remote-file
   (stevedore/script (~lib/etc-hosts))
   :owner "root:root"
   :mode 644
   :content ))
