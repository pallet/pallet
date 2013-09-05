(ns pallet.task
  "Task helpers, that do not have any dependencies in pallet.")

(let [{:keys [major minor]} *clojure-version*]
  (when (and (= major 1) (< minor 4))
    (throw (Exception. "Pallet requires at least clojure 1.4.0"))))

(defn report-error
  "Report a message to *err*."
  [msg]
  (binding [*out* *err*]
    (println msg)))

(defn abort
  "Abort a task, with the specified error message, and no stack trace."
  [msg]
  (report-error msg)
  (throw (ex-info msg {:exit-code 1})))

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
