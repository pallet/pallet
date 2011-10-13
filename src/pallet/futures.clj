(ns pallet.futures
  "Keep track of operations started by pallet"
  (:require
   [clojure.stacktrace :as stacktrace]
   [clojure.tools.logging :as logging])
  (:import
   java.util.concurrent.CancellationException
   java.util.concurrent.ExecutionException
   java.util.concurrent.Future))

(def
  ^{:doc "Keep track of pending operations, so they can be cancelled."
    :private true}
  pending-futures (atom (list)))

(defn- remove-done
  "Remove all completed futures"
  [futures]
  (remove #(.isDone ^Future %1) futures))

(defn add
  "Add a sequence of futures to the list of pending operations. Returns
   its argument."
  [futures]
  (do
    (swap! pending-futures #(concat (remove-done %1) %2) futures)
    futures))

(defn cancel-all
  "Cancel all pending parallel operations"
  []
  (swap! pending-futures #(do (doseq [^Future f %] (.cancel f true)) '()))
  nil)

(defn deref-with-logging
  "Deref a future with logging, returning nil if exception thrown.
   `operation-label` appears in each log message generated."
  [f operation-label]
  (try
    @f
    (catch CancellationException e
      (logging/warnf "%s cancelled : %s" operation-label (.getMessage e)))
    (catch InterruptedException e
      (logging/warnf "%s interrupted" operation-label))
    (catch ExecutionException e
      (let [cause (stacktrace/root-cause e)]
        (logging/errorf
         cause "%s exception: %s" operation-label (.getMessage cause)))
      (logging/debugf (.getCause e) "%s exception" operation-label))))
