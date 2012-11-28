(ns pallet.debug
  "Helpers for debugging."
  (:require
   [clojure.tools.logging :as logging]))


(defn log-session
  "A crate function that will log the session map at the debug level, using
   the supplied format string.

       (log-session session \"The session is %s\")"
  ([] (log-session "%s"))
  ([format-string]
     (fn [session]
       (logging/debug (format format-string (pr-str session)))
       [nil session])))

(defmacro debugf
  "Log at DEBUG level."
  [format-string & args]
  `(fn [session#]
     (logging/debugf ~format-string ~@args)
     [nil session#]))

(defn print-session
  "A crate function that will print the session map to *out*, using the supplied
   format string.

       (print-session \"The session is %s\")"
  ([] (print-session "%s"))
  ([format-string]
     (fn [session]
       (println (format format-string (pr-str session)))
       [nil session])))

(defn assertf
  "Assert a condition"
  ([expr format-string & args]
     (fn [session]
       (when-not expr
         (throw (Exception. (apply format format-string args))))
       [true session])))
