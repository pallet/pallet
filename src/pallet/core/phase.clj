(ns pallet.core.phase
  "# Pallet phase maps.

Phase maps provide a way of naming functions at runtime.  A phase map
is just a hash-map with keys that are keywords (the phases) and values
that are pallet plan functions.

Phase maps enable composition of operations across heterogenous nodes."
  (:require
   [clojure.core.async :as async :refer [go <!]]
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> for> letfn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.tools.logging :as logging :refer [debugf tracef]]
   [pallet.contracts
    :refer [check-converge-options
            check-group-spec
            check-lift-options
            check-node-spec
            check-server-spec
            check-user]]
   [pallet.core.api :as api]
   [pallet.core.middleware :as middleware]
   [pallet.core.plan-state :as plan-state]
   [pallet.core.tag :as tag]
   [pallet.core.user :refer [obfuscated-passwords]]
   [pallet.node :as node]
   [pallet.utils :refer [deep-merge]]))

;;; # Phase specification functions
(ann phase-args [Phase -> (Nilable (Seqable Any))])
(defn phase-args [phase]
  (if (keyword? phase)
    nil
    (rest phase)))

(ann phase-kw [Phase -> Keyword])
(defn phase-kw [phase]
  (if (keyword? phase)
    phase
    (first phase)))

(ann target-phase [PhaseTarget Phase -> [Any * -> Any]])
(defn target-phase [phases-map phase]
  (tracef "target-phase %s %s" phases-map phase)
  ;; TODO switch back to keyword invocation when core.typed can handle it
  (get phases-map (phase-kw phase)))

;;; # Phase metadata
(defn phases-with-meta
  "Takes a `phases-map` and applies the default phase metadata and the
  `phases-meta` to the phases in it."
  [phases-map phases-meta default-phase-meta]
  (reduce-kv
   (fn [result k v]
     (let [dm (default-phase-meta k)
           pm (get phases-meta k)]
       (assoc result k (if (or dm pm)
                         ;; explicit overrides default
                         (vary-meta v #(merge dm % pm))
                         v))))
   nil
   (or phases-map {})))

;;; # Execution
(ann execute-phase [BaseSession Node Phase -> PlanResult])
(defn execute-phase
  "Apply phase to target.
  Phase is either a keyword, or a vector of keyword and phase arguments."
  ([session node phases-map phase execute-f]
     (let [plan-fn (target-phase phases-map phase)]
       (middleware/execute session node plan-fn execute-f)))
  ([session node phases-map phase]
     (let [plan-fn (target-phase phases-map phase)]
       (middleware/execute session node plan-fn))))


;; (ann execute-plan-fns [BaseSession TargetMapSeq Phase
;;                        -> (Seqable (ReadOnlyPort PlanResult))])
;; (defn execute-phase
;;   "Apply phase to targets.
;;   Phase is either a keyword, or a vector of keyword and phase arguments."
;;   [session targets phase]
;;   (for> :- (ReadOnlyPort PlanResult)
;;         [target :- TargetMap targets
;;          :let [plan (target-phase target phase)]
;;          :when plan]
;;     (api/execute session target plan)))




;;; # phase metadata


;;; ## Execution settings

;;; #### Phase Metadata

;;; Phase metadata is used to control how a phase is executed.  There are
;;; currently two possible keys for the metadata attached to a plan function,
;;; `:execution-settings-f` and `:phase-execution-f`.

;;; Phase metadata is merged when phase functions are composed.

;; (defn execute-with-image-credentials-metadata
;;   "Returns a metadata map that specifies a phase should be run using the image
;; credentials (rather than the admin-user).  The map is suitable for use as a
;; value in a map passed to the `:phases-meta` clause of a `server-spec` or
;; `group-spec`."
;;   []
;;   {:execution-settings-f (environment-image-execution-settings)})

;; (defn execute-on-unflagged-metadata
;;   "Returns a metadata map that specifies a phase should be run only if the
;; specified `flag-kw` is not set on a node.  The map is suitable for use as a
;; value in a map passed to the `:phases-meta` clause of a `server-spec` or
;; `group-spec`."
;;   [flag-kw]
;;   {:phase-execution-f (execute-on-unflagged flag-kw)})
