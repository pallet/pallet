(ns pallet.crate.etc-hosts
  "/etc/hosts file."
 (:require
   [pallet.argument :as argument]
   [pallet.common.deprecate :as deprecate]
   [pallet.compute :as compute]
   [pallet.parameter :as parameter]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.string :as string])
 (:use
  [pallet.actions :only [remote-file]]
  [pallet.parameter :only [update-settings]]
  [pallet.phase :only [def-crate-fn defcrate]]))

(defn- format-entry
  [entry]
  (format "%s %s"  (key entry) (name (val entry))))

(def-crate-fn host
  "Declare a host entry"
  [address names]
  (update-settings :hosts nil merge {address names}))

(def-crate-fn hosts-for-group
  "Declare host entries for all nodes of a group"
  [group-name & {:keys [private-ip]}]
  [ip (m-result (if private-ip compute/private-ip compute/primary-ip))
   group-nodes (session/nodes-in-group group-name)]
  (update-settings :hosts nil
   merge
   (into
    {}
    (map #(vector (ip %) (compute/hostname %)) group-nodes))))

(def ^{:private true} localhost
  {"127.0.0.1" "localhost localhost.localdomain"})

(defn- format-hosts*
  [entries]
  (string/join "\n" (map format-entry entries)))

(defn- format-hosts
  [session]
  (format-hosts*
   (conj localhost (parameter/get-target-settings session :hosts nil nil))))

(defcrate hosts
  "Writes the hosts files"
  (remote-file
   (stevedore/script (~lib/etc-hosts))
   :owner "root:root"
   :mode 644
   :content (argument/delayed [session] (format-hosts session))))
