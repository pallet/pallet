(ns pallet.exception
  "Exception functions"
  (:require
   [clojure.core.typed :refer [ann Seqable]]
   [clojure.java.io :as io]
   [clojure.string :as string]))

(defn ^:internal compiler-exception
  "Return a compiler exception.  This is should be used to throw in
  macros defining top level forms, as clojure doesn't wrap these in
  Compiler$CompilerException."
  ([form msg data]
     (clojure.lang.Compiler$CompilerException.
      (.getName (io/file *file*))
      (or (-> form meta :line) 1)
      (or (-> form meta :column) 1)
      (if (seq data)
        (ex-info msg data)
        (Exception. msg))))
  ([form msg]
     (compiler-exception form msg nil)))

(defn domain-error?
  "Predicate to test for a domain error.  Domain errors are not thrown
  but are recorded in the results."
  [exception]
  (:pallet/domain (ex-data exception)))

(defn domain-info
  "Return an exception that is marked as a domain exception."
  ([msg data]
     (domain-info msg data nil))
  ([msg data cause]
     (ex-info msg (merge {:pallet/domain true} data) cause)))

(ann combine-exceptions [(Seqable Throwable) -> (U nil Throwable)])
(defn ^:internal combine-exceptions
  "Wrap a sequence of exceptions into a single exception.  The first
  element of the sequence is used as the cause of the composite
  exception.  Removes any nil values in the input exceptions
  sequence."
  [exceptions]
  (if-let [exceptions (seq (remove nil? exceptions))]
    (let [cause (or (first (filter (complement domain-error?) exceptions))
                    (first exceptions))]
      ;; always wrap, so we get a full stacktrace, not just a threadpool
      ;; trace.
      (ex-info
       (let [s (string/join ". " (map #(str %) exceptions))]
         (if (string/blank? s) (pr-str exceptions) s))
       (merge
        (if (domain-error? cause)
          {:pallet/domain true})
        {:exceptions exceptions})
       cause))))
