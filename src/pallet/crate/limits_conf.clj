(ns pallet.crate.limits-conf
  "Configure the /etc/security/limits.conf file."
  (:require
   [clojure.string :as string]
   [pallet.actions :refer [exec-checked-script file remote-file]]
   [pallet.api :refer [plan-fn] :as api]
   [pallet.script :refer [defscript defimpl]]
   [pallet.script.lib :as lib]
   [pallet.settings :refer [assoc-settings get-settings update-settings]]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]))

;;; # Settings
(defn default-settings
  []
  {:config-file "/etc/security/limits.conf"
   :owner "root"
   :group "root"})

(defn- normalise-entry
  "Normalise an entry into map form, which is how the entries are kept in
  the settings map."
  [entry]
  {:pre [(or (vector? entry) (map? entry))]}
  (if (map? entry)
    entry
    (zipmap [:domain :type :item :value] entry)))

(defn settings
  "Configure limits.conf settings.
  The :entries value is a sequence of maps and vectors.  Each map must
  specify :domain, :type, :item and :value fields.  Each vectors specifies
  strings for the fields (in domain, type, item, value order).  The type and
  item values may optionally be keywords."
  [{:keys [entries config-file instance-id] :as settings}]
  (let [settings (merge (default-settings) settings)
        settings (update-in settings [:entries] #(mapv normalise-entry %))]
    (assoc-settings
     :limits-conf settings (select-keys settings [:instance-id]))))

(defn ulimit
  "Declare a host entry.  This may be called multiple times to build limits
  incrementally.

  A host entry can be either a map with :domain, :type, :item and :value keys,
  or a vector specifying strings for the fields (in domain, type, item, value
  order).  The type and item values may optionally be keywords."
  [entry & {:keys [instance-id] :as options}]
  {:pre [(or (vector? entry) (map? entry))]}
  (update-settings :limits-conf options
                   update-in [:entries] conj (normalise-entry entry)))

;;; # Config file
(defn- format-entry
  [{:keys [domain type item value] :or {type "-"}}]
  (format "%s %s %s %s" domain (name type) (name item) value))

(defn- format-host-limits
  [entries]
  (string/join "\n" (map format-entry entries)))

(defn configure
  "Writes the limit.conf file."
  [{:keys [instance-id] :as options}]
  (let [{:keys [config-file entries group owner]}
        (get-settings :limits-conf options)]
    (remote-file
     config-file
     :owner owner
     :group group
     :mode 644
     :content (format-host-limits entries))))

;;; # Server spec
(defn server-spec
  "Return a server spec for limits.conf configuration.  See the `settings`
function for options to the settings map"
  [{:keys [entries config-file] :as settings}
   & {:keys [instance-id] :as options}]
  (api/server-spec
   :phases {:settings (plan-fn
                        (pallet.crate.limits-conf/settings
                         (merge settings options)))
            :configure (plan-fn
                         (configure options))}))
