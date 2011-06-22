(ns pallet.debug
  "Helpers for debugging."
  (:require
   [clojure.tools.logging :as logging]))


(defn log-session
  "A crate function that will log the session map at the debug level, using
   the supplied format string.

       (log-session session \"The session is %s\")"
  ([session]
     (log-session session "%s"))
  ([session format-string]
     (logging/debugf format-string (pr-str session))
     session))

(defn print-session
  "A crate function that will print the session map to *out*, using the supplied
   format string.

       (print-session session \"The session is %s\")"
  ([session]
     (print-session session "%s"))
  ([session format-string]
     (println (format format-string (pr-str session)))
     session))
