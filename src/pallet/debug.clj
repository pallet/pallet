(ns pallet.debug
  "Helpers for debugging."
  (:require
   [clojure.tools.logging :as logging]
   [pallet.core.session :refer [session]]))

(defn log-session
  "A crate function that will log the session map at the debug level, using
   the supplied format string.

       (log-session session \"The session is %s\")"
  ([] (log-session "%s"))
  ([format-string]
     (logging/debug (format format-string (pr-str (session))))))

(defn print-session
  "A crate function that will print the session map to *out*, using the supplied
   format string.

       (print-session \"The session is %s\")"
  ([] (print-session "%s"))
  ([format-string]
     (println (format format-string (pr-str (session))))))

(defmacro assertf
  "Assert a condition"
  [expr format-string & args]
  `(assert ~expr (format ~format-string ~@args)))
