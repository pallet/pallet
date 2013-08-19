(ns pallet.crate.initd
  "Provides service supervision via initd"
  (:require
   [clojure.tools.logging :refer [debugf]]
   [pallet.actions :refer [exec-checked-script remote-file]]
   [pallet.actions.direct.service :refer [service-impl]]
   [pallet.api :as api]
   [pallet.api :refer [plan-fn]]
   [pallet.core.session :refer [session]]
   [pallet.crate :refer [get-settings update-settings]]
   [pallet.crate.service
    :refer [service-supervisor
            service-supervisor-available?
            service-supervisor-config]]
   [pallet.script.lib :refer [etc-init file]]
   [pallet.stevedore :refer [fragment]]
   [pallet.utils :refer [apply-map]]))

(defn init-script-path
  "Return the init script path for the given service name."
  [service-name]
  (fragment (file (etc-init) ~service-name)))

(defmethod service-supervisor-available? :initd
  [_]
  true)

(defn write-service
  [service-name {:keys [init-file] :as service-options} options]
  (when init-file                       ; enable use of pre-installed init files
    (apply-map
     remote-file
     (init-script-path service-name)
     :owner "root" :group "root" :mode "0755"
     :literal true
     init-file)))

(defn jobs
  "Write out job definitions."
  [{:keys [instance-id] :as options}]
  (let [{:keys [jobs]} (get-settings :initd {:instance-id instance-id})]
    (debugf "Writing service files for %s jobs" (count jobs))
    (doseq [[job {:keys [run-file] :as service-options}] jobs
            :let [service-name (name job)]]
      (write-service service-name service-options options))))

(defmethod service-supervisor-config :initd
  [_
   {:keys [service-name init-file] :as service-options}
   {:keys [instance-id] :as options}]
  (debugf "Adding service settings for %s" service-name)
  (update-settings
   :initd options assoc-in [:jobs (keyword service-name)] service-options))

(defmethod service-supervisor :initd
  [_
   {:keys [service-name]}
   {:keys [action if-flag if-stopped instance-id]
    :or {action :start}
    :as options}]
  (exec-checked-script
   (str "Initd " (name action) " " service-name)
   ~(apply-map service-impl (session) service-name
               (assoc options :service-impl :initd))))

(defn server-spec [settings & {:keys [instance-id] :as options}]
  (api/server-spec
   :phases {:configure (plan-fn (jobs options))}))
