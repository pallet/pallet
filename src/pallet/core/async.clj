(ns pallet.core.async
  "Asynchronous execution of pallet plan functions."
  (:require
   [clojure.core.async :as async :refer [go <! thread]]
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> for> letfn> loop>
            inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.tools.logging :refer [debugf]]
   [pallet.async :refer [go-logged map-async timeout-chan]]
   [pallet.utils :refer [deep-merge]]))

(ann ^:no-check action-errors?
  [(Seqable (ReadOnlyPort ActionResult)) -> (Nilable PlanResult)])
(defn action-errors?
  "Check for errors reported by the sequence of channels.  This provides
  a synchronisation point."
  [chans]
  (->> chans
       (mapv (fn> [c :- (ReadOnlyPort ActionResult)]
               (timeout-chan c (* 5 60 1000))))
       async/merge
       (async/reduce
        (fn> [r :- (Nilable PlanResult)
              v :- (Nilable ActionResult)]
          (or r (and (nil? v) {:error {:timeout true}})
              (and (:error v) (select-keys v [:error]))))
        nil)))

(defn- map-targets
  "Returns a channel which will return the result of applying f to
  targets, reducing the results into a tuple containing a result
  vector and a plan-state."
  [f targets plan-state]
  (debugf "map-targets on %s targets" (count targets))
  (->> (map f targets)
       (async/merge)
       (async/reduce
        (fn [[results plan-state] r]
          [(conj results r) (deep-merge plan-state (:plan-state r))])
        [[] plan-state])))

(defn thread-fn
  "Execute function f in a new thread, return a channel for the result.
Return a tuple of [function return value, exception], where only one
of the values will be non-nil."
  [f]
  (thread
   (try
     [(f) nil]
     (catch Throwable t
       [nil t]))))

(defn map-thread
  "Apply f to each element of coll, using a separate thread for each element.
  Return a non-lazy sequence of channels for the results."
  [f coll]
  (doall (map thread-fn coll)))
