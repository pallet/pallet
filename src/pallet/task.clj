(ns pallet.task
  "Task helpers")

(def
  ^{:doc "An exception instance to use for terminating the task, without
          a stack trace"}
  exit-task-exception (Exception.))

(defn report-error
  "Report a message to *err*."
  [msg]
  (binding [*out* *err*]
    (println msg)))

(defn abort
  "Abort a task, with the specified error message, and no stack trace."
  [msg]
  (report-error msg)
  (throw exit-task-exception))

(defn parse-as-qualified-symbol
  "Convert the given symbol-string into a namespace qualified symbol.
   Returns a vector of ns and symbol"
  [^String symbol-string]
  {:pre [(string? symbol-string)]}
  (when (re-matches #"[^/]+/[^/]+" symbol-string)
    (when-let [sym (symbol symbol-string)]
      [(symbol (namespace sym)) sym])))

(defn maybe-resolve-symbol-string
  "Try and resolve a symbol-string to a var value"
  [symbol-string]
  (when-let [[ns sym] (parse-as-qualified-symbol symbol-string)]
    (try
      (require ns)
      (when-let [v (find-var sym)]
        (var-get v))
      (catch java.io.FileNotFoundException e
        nil))))
