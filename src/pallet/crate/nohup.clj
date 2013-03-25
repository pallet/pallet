(ns pallet.crate.nohup
  "Provides supervision via nohup.  Note that this is very limited, and not
  really recommended for production use."
  (:require
   [clojure.tools.logging :refer [debugf warnf]]
   [pallet.action :refer [with-action-options]]
   [pallet.actions
    :refer [directory exec-checked-script plan-when plan-when-not remote-file]
    :as actions]
   [pallet.api :as api :refer [plan-fn]]
   [pallet.crate :refer [get-settings target-flag? update-settings]]
   [pallet.crate.service
    :refer [service-supervisor service-supervisor-available?
            service-supervisor-config]]
   [pallet.script.lib :refer [state-root file flag?]]
   [pallet.stevedore :refer [fragment script]]
   [pallet.utils :refer [apply-map]]))

(defn nohup-path []
  (fragment (file (state-root) "pallet" "nohup-service")))

(defn service-script-file
  ([service-name filename]
     (fragment (file (nohup-path) ~service-name ~filename)))
  ([service-name]
     (fragment (file (nohup-path) ~service-name))))

(defn service-script-path [service-name]
  (service-script-file service-name "run"))

(defn service-script-output-path [service-name]
  (service-script-file service-name "nohup.out"))

(defn service-script-failed-path [service-name]
  (service-script-file service-name "nohup.failed"))

(defmethod service-supervisor-available? :nohup
  [_]
  true)

(defn write-service
  [service-name {:keys [run-file user] :as service-options} options]
  (directory (service-script-file service-name) :owner user)
  (apply-map
   remote-file
   (service-script-path service-name)
   :mode "0755"
   :owner user
   run-file))

(defn jobs
  "Write out job definitions."
  [{:keys [instance-id] :as options}]
  (let [{:keys [jobs]} (get-settings :nohup {:instance-id instance-id})]
    (debugf "Writing service files for %s jobs" (count jobs))
    (doseq [[job {:keys [run-file] :as service-options}] jobs
            :let [service-name (name job)]]
      (write-service service-name service-options options))))

(defmethod service-supervisor-config :nohup
  [_ {:keys [service-name run-file user] :as service-options} options]
  (debugf "Adding service settings for %s" service-name)
  (update-settings
   :nohup options assoc-in [:jobs (keyword service-name)] service-options))

(defn start-nohup-service [service-name user]
  (actions/file (service-script-failed-path service-name) :action :delete)
  (with-action-options {:sudo-user user
                        :script-dir (service-script-file service-name)}
    (exec-checked-script
     (str "Start " service-name " via nohup")
     ("("
      (chain-or
       ("nohup" ~(service-script-path service-name)
        ">" (service-script-output-path ~service-name))
       ("touch" (service-script-failed-path ~service-name)))
      "&" ")")
     ("sleep" 5)
     (not (file-exists? (service-script-failed-path ~service-name))))))

(defn stop-nohup-service [service-name user]
  (with-action-options {:sudo-user user}
    (exec-checked-script
     (str "Kill " service-name " via killall")
     ("killall" (quoted ~service-name)))))

(defmethod service-supervisor :nohup
  [_
   {:keys [service-name user process-name]}
   {:keys [action if-flag if-stopped instance-id]
    :or {action :start}
    :as options}]
  (debugf "Controlling service %s, :action %s, :if-stopped %s, :if-flag"
          service-name action if-stopped (pr-str if-flag))
  (let [process-name (or process-name service-name)]
    (if (#{:enable :disable :start-stop} action)
      (warnf "Requested action %s on service %s not implemented via nohup"
             action service-name)
      (if if-flag
        (plan-when (target-flag? if-flag)
          (exec-checked-script
           (str ~(name action) " " ~service-name " if config changed")
           (~(service-script-path service-name) ~(name action))))
        (if if-stopped
          (case action
            :start (plan-when-not
                       (fragment ("pgrep" -f (quoted ~process-name)))
                     (start-nohup-service service-name user))
            :stop nil
            :restart nil)
          (case action
            :start (start-nohup-service service-name user)
            :stop (stop-nohup-service process-name user)
            :restart (do
                       (stop-nohup-service process-name user)
                       (start-nohup-service service-name user))))))))

(defn server-spec [settings & {:keys [instance-id] :as options}]
  (api/server-spec
   :phases {:configure (plan-fn (jobs options))}))
