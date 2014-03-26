(ns pallet.log
  "Logging configuration for pallet."
  (:require
   [com.palletops.log-config.timbre
    :refer [context-msg domain-msg format-with-domain-context]]
   [taoensso.timbre :refer [merge-config! str-println]]
   [taoensso.timbre.tools.logging :refer [use-timbre]]))

(def timbre-config
  "A basic timbre configuration for use with pallet."
  {;; Fns (applied right-to-left) to transform/filter appender fn args.
   ;; Useful for obfuscating credentials, pattern filtering, etc.
   :middleware [context-msg domain-msg]

   ;;; Control :timestamp format
   :timestamp-pattern "yyyy-MM-dd HH:mm:ss" ; SimpleDateFormat pattern
   :timestamp-locale  nil ; A Locale object, or nil

   ;; Output formatter used by built-in appenders. Custom appenders may (but are
   ;; not required to use) its output (:output). Extra per-appender opts can be
   ;; supplied as an optional second (map) arg.
   :fmt-output-fn format-with-domain-context

   :shared-appender-config {:spit-filename "logs/pallet.log"}

   :appenders
   {:standard-out
    {:doc "Prints to *out*/*err*. Enabled by default."
     :min-level :info :enabled? true :async? false :rate-limit nil
     :fn (fn [{:keys [error? output]}] ; Can use any appender args
           (binding [*out* (if error? *err* *out*)]
             (str-println output)))}

    :spit
    {:doc "Spits to `(:spit-filename :shared-appender-config)` file."
     :min-level nil :enabled? true :async? false :rate-limit nil
     :fn (fn [{:keys [ap-config output]}] ; Can use any appender args
           (when-let [filename (:spit-filename ap-config)]
             (try (spit filename (str output "\n") :append true)
                  (catch java.io.IOException _))))}}})

(defn default-log-config
  "Set a default log configuration"
  []
  (merge-config! timbre-config)
  (use-timbre))
