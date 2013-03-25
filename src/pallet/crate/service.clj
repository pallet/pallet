(ns pallet.crate.service
  "Run services under supervision.

This crate provides a framework for a process under supervision.  It can be
extended by different supervision providers, by providing methods for the
various multi-methods.")

;;; Service Supervisor SPI

;;; New supervisors can be added by providing methods for these multimethods.
;;; They are dispatched on a `supervisor` keyword.
(defmulti service-supervisor-available?
  "Predicate to test if a given service implementation is available."
  (fn [supervisor] supervisor))

(defmethod service-supervisor-available? :default
  [supervisor] false)

(defmulti service-supervisor
  "Provides an open dispatched supervisor implementation.

Implement a method dispatched on the supervisor keyword to add a
supervisor implementation.

In options:

`:instance-id`
: specifies the supervisor instance-id, not the facility instance-id."
  (fn [supervisor settings options] supervisor))

(defmulti service-supervisor-config
  "Configure a service implementation based on configuration map.  The `config`
map is specific to the supervisor.  The initial enabled status of a config is
supervisor specific."
  (fn [supervisor config supervisor-options]
    {:pre [supervisor]}
    (when-not (service-supervisor-available? supervisor)
      (throw
       (ex-info
        (str (name supervisor)
             " supervisor requested, but the"
             (name supervisor) " crate is not on the classpath.")
        {:type :pallet/not-on-classpath
         :resource supervisor})))
    supervisor))

;;; # Crate Implementer API

;;; ## Service Control
;;; This can be called by a crate to control a service
(defn service
  "Control a process under supervision.

The settings map must provide `:service-name` and `:supervisor` keys.  The
`:supervisor` key specifies a keyword for the supervisor provider to dispatch
to.  The `:service-name` provides a service name to be used by the supervision
provider.

`:action`
One of `:enable`, `:disable`, `:start`, `:stop`, `:restart`, `:status`. Defaults
to :start.

`:if-stopped`
Flag to only apply the action if the service is currently stopped.

`:if-flag`
Flag to only apply the action only if the specified flag is set.

`:instance-id`
Specifies an instance id, should there be more than one instance of the
supervisor (not the facility)."
  [{:keys [supervisor service-name] :as settings}
   {:keys [action if-flag if-stopped instance-id] :as options}]
  {:pre [supervisor]}
  (service-supervisor supervisor settings options))

;;; ## Service Configuration
;;; Provide service supervision helpers for crates.
(defmulti supervisor-config-map
  "Return a service configuration map for the given supervisor and facility.

A method should be implemented in each crate for each supervisor to be
supported."
  (fn [facility {:keys [supervisor] :as settings} options]
    [facility supervisor]))


;;; Provide a default method to generate a meaningful error message for the case
;;; that the crate (facility) has not provided a supervisor configuration
;;; for the given supervisor.
(defmethod supervisor-config-map :default
  [facility {:keys [supervisor] :as settings} _]
  (throw
   (ex-info
    (str (name supervisor)
         " supervisor requested for " (name facility)
         ", but this has not been implemented.")
    {:type :pallet/unimplemented-supervisor
     :supervisor supervisor
     :facility facility})))

(defn supervisor-config
  "Configure service supervision for facility based on configuration map.

Supervisor specific options are specified under the supervisor key in the
settings map.

This is intended to be called at the crate level."
  [facility {:keys [supervisor] :as settings} options]
  (service-supervisor-config
   supervisor
   (supervisor-config-map facility settings options)
   (get settings supervisor)))
