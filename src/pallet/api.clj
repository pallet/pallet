(ns pallet.api
  "# Pallet API"
  ;; (:require
  ;;  [clojure.core.async :as async :refer [go <!]]
  ;;  [clojure.java.io :refer [input-stream resource]]
  ;;  [clojure.pprint :refer [print-table]]
  ;;  [clojure.set :refer [union]]
  ;;  [clojure.string :refer [blank?]]
  ;;  [clojure.tools.logging :as logging]
  ;;  [pallet.action-options :refer [action-options-key]]
  ;;  [pallet.utils.async :refer [go-logged timeout-chan]]
  ;;  [pallet.compute :as compute]
  ;;  [pallet.configure :as configure]
  ;;  [pallet.core.operations :as ops]
  ;;  [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
  ;;  [pallet.core.primitives
  ;;   :refer [async-operation bootstrapped-meta exec-operation
  ;;           execute-on-unflagged phases-with-meta unbootstrapped-meta]]
  ;;  [pallet.core.recorder :refer [results]]
  ;;  [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
  ;;  [pallet.crate.os :refer [os]]
  ;;  [pallet.environment :refer [group-with-environment merge-environments]]
  ;;  [pallet.executors :refer [default-executor]]
  ;;  [pallet.node :refer [node-map node?]]
  ;;  [pallet.plan :as api
  ;;   :refer [environment-image-execution-settings
  ;;           phase-errors session-for stop-execution-on-error
  ;;           target-fn target-phase-fn]]
  ;;  [pallet.plugin :refer [load-plugins]]
  ;;  [pallet.spec
  ;;   :refer [merge-spec-algorithm merge-specs node-has-group-name?]]
  ;;  [pallet.sync :refer [enter-phase-targets]]
  ;;  [pallet.sync.in-memory :refer [in-memory-sync-service]]
  ;;  [pallet.thread-expr :refer [when->]]
  ;;  [pallet.user :as user]
  ;;  [pallet.utils :refer [apply-map maybe-update-in total-order-merge]])
  )


;;; ## Pallet version
;; (let [v (atom nil)
;;       properties-path "META-INF/maven/com.palletops/pallet/pom.properties"]
;;   (defn version
;;     "Returns the pallet version."
;;     []
;;     (or
;;      @v
;;      (if-let [path (resource properties-path)]
;;        (with-open [in (input-stream path)]
;;          (let [properties (doto (java.util.Properties.) (.load in))]
;;            {:version (.getProperty properties "version")
;;             :revision (.getProperty properties "revision")}))
;;        {:version :unknown :revision :unknown}))))


;; ;;; ## Compute Service
;; ;;;
;; ;;; The compute service is used to communicate with the cloud provider
;; (defn compute-service
;;   "Returns a compute service object, used to perform actions on a cloud
;;   provider."
;;   [service-or-provider-name & options]
;;   (apply configure/compute-service service-or-provider-name options))


;; ;;; ### Admin user
;; (defn make-user
;;   "Creates a User record with the given username and options. Generally used
;;    in conjunction with *admin-user* and pallet.api/with-admin-user, or passed
;;    to `lift` or `converge` as the named :user argument.

;;    Options:
;;     - :public-key-path (defaults to ~/.ssh/id_rsa.pub)
;;     - :private-key-path (defaults to ~/.ssh/id_rsa)
;;     - :passphrase
;;     - :password
;;     - :sudo-password (defaults to :password)
;;     - :no-sudo"
;;   [username & {:keys [public-key-path private-key-path passphrase
;;                       password sudo-password no-sudo sudo-user] :as options}]
;;   (check-user
;;    (user/make-user
;;     username
;;     (merge
;;      (if (:password options)
;;        {:sudo-password (:password options)}
;;        {:private-key-path (user/default-private-key-path)
;;         :public-key-path (user/default-public-key-path)})
;;      options))))

;; ;; (defmacro with-admin-user
;; ;;   "Specify the admin user for running remote commands.  The user is specified
;; ;;    either as pallet.utils.User record (see the pallet.utils/make-user
;; ;;    convenience fn) or as an argument list that will be passed to make-user.

;; ;;    This is mainly for use at the repl, since the admin user can be specified
;; ;;    functionally using the :user key in a lift or converge call, or in the
;; ;;    environment."
;; ;;   [user & exprs]
;; ;;   `(binding [user/*admin-user* ~user]
;; ;;     ~@exprs))

;; (defn print-nodes
;;   "Print the targets of an operation"
;;   [nodes]
;;   (let [ks [:primary-ip :private-ip :hostname :group-name :roles]]
;;     (print-table ks
;;                  (for [node nodes
;;                        :let [node (node-map node)]]
;;                    (select-keys node ks)))))

;; (defn print-targets
;;   "Print the targets of an operation"
;;   [op]
;;   (let [ks [:primary-ip :private-ip :hostname :group-name :roles]]
;;     (print-table ks
;;                  (for [{:keys [node roles]} (:targets op)]
;;                    (assoc (select-keys (node-map node) ks)
;;                      :roles roles)))))



;; ;; (defn execute-phase-fn
;; ;;   "Execute matching targets and phase functions."
;; ;;   [target phase-fn service-state
;; ;;    {:keys [recorder plan-state sync-service
;; ;;            executor execute-status-fn]
;; ;;     :as components}]
;; ;;   (go-logged
;; ;;    (logging/debugf "execute-phase-fn on %s" target)
;; ;;    (phase-fn sync-service (session-for target service-state components))))

;; ;; (defn execute-target-phase-fns
;; ;;   "Execute matching targets and phase functions."
;; ;;   [target-phase-fns service-state
;; ;;    {:keys [recorder plan-state sync-service
;; ;;            executor execute-status-fn]
;; ;;     :as components}]
;; ;;   (logging/debugf "execute-target-phase-fns")
;; ;;   (->>
;; ;;    (for [[target phase-fn] target-phase-fns]
;; ;;      (execute-phase-fn target phase-fn service-state components))
;; ;;    (async/merge)
;; ;;    (async/reduce (constantly true) true)))

;; ;; (defn phase-fn-for
;; ;;   [target phase]
;; ;;   (logging/debugf "phase-fn-for %s" phase)
;; ;;   (vector
;; ;;    target
;; ;;    (target-fn
;; ;;     target
;; ;;     (mapv #(target-phase-fn target %) phase))))

;; ;;; # Session
;; (defn default-components
;;   []
;;   {:recorder (in-memory-recorder)
;;    :plan-state (in-memory-plan-state)
;;    :sync-service (in-memory-sync-service)
;;    :executor #'default-executor})

;; (defn session
;;   "Creates a session object with the given components.
;;   Default components will be used if not specified."
;;   [{:keys [recorder plan-state executor service-state action-options]
;;     :as options}]
;;   (session/create (merge default-components options)))

;; ;;; # Target queries
;; (defn service-targets
;;   "Return a sequence of targets for the groups in compute-service."
;;   [compute-service groups]
;;   (api/service-state compute-service groups))

;; ;;; # Operations
;; (ann os-detect-phases [-> (Seqable PlanFn)])
;; (defn os-detect-phases
;;   "Return OS detection phases.  These phases will update the plan-state
;;   as a side effect.  There are two phases returned; one for bootstrapped
;;   nodes, and one for non-bootstrapped nodes.  Both should always be run
;;   to ensure that the detection is applied."
;;   []
;;   [(vary-meta (plan-fn (os)) merge unbootstrapped-meta)
;;    (vary-meta (plan-fn (os)) merge bootstrapped-meta)])



;; ;;; # OS Detection

;; (ann os-detect [BaseSession TargetMapSeq
;;                 -> (Seqable (ReadOnlyPort PlanResult))])
;; (defn os-detect
;;   "Apply OS detection to targets.  The results of the detection will
;;   be put into the plan-state."
;;   [session targets]
;;   (execute-plan-fns session targets (os-detect-phases)))
