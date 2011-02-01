(ns pallet.debug
  "Helpers for debugging."
  (:require
   [clojure.contrib.logging :as logging]))


(defn log-request
  "A crate function that will log the request map at the debug level, using
   the supplied format string.

       (log-request request \"The request is %s\")"
  ([request]
     (log-request request "%s"))
  ([request format-string]
     (logging/debug (format format-string (pr-str request)))
     request))

(defn print-request
  "A crate function that will print the request map to *out*, using the supplied
   format string.

       (print-request request \"The request is %s\")"
  ([request]
     (print-request request "%s"))
  ([request format-string]
     (println (format format-string (pr-str request)))
     request))
