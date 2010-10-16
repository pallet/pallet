(ns pallet.crate.etc-hosts
  "/etc/hosts file."
 (:require
   [pallet.argument :as argument]
   [pallet.compute :as compute]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore]
   [pallet.request-map :as request-map]
   [pallet.resource.file :as file]
   [pallet.resource.filesystem-layout :as filesystem-layout]
   [pallet.resource.remote-file :as remote-file]
   [clojure.string :as string]))

(defn- format-entry
  [entry]
  (format "%s %s"  (key entry) (name (val entry))))

(defn host
  "Declare a host entry"
  [request address names]
  (->
   request
   (parameter/update-for-target [:hosts] merge {address names})))

(defn hosts-for-tag
  "Declare host entries for all nodes of a tag"
  [request tag & {:keys [private-ip]}]
  (let [ip (if private-ip compute/private-ip compute/primary-ip)]
    (->
     request
     (parameter/update-for-target
      [:hosts]
      merge
      (into
       {}
       (map #(vector (ip %) (compute/hostname %))
            (request-map/nodes-in-tag request tag)))))))

(def ^{:private true} localhost
  {"127.0.0.1" "localhost localhost.localdomain"})

(defn- format-hosts*
  [entries]
  (string/join "\n" (map format-entry entries)))

(defn- format-hosts
  [request]
  (format-hosts*
   (conj localhost (parameter/get-for-target request [:hosts] nil))))

(defn hosts
  "Writes the hosts files"
  [request]
  (-> request
      (remote-file/remote-file
       (stevedore/script (etc-hosts))
       :owner "root:root"
       :mode 644
       :content (argument/delayed [request] (format-hosts request)))))
