(ns pallet.utils.edn
  "EDN utilities"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :refer [file reader]])
  (:import
   [clojure.lang Keyword LineNumberingPushbackReader]))

(defn load-edn
  "Load EDN File.  If the file is invalid, an exception is thrown
  specifying the location in the file at which the invalid syntax
  occured."
  [f]
  (with-open [rdr (LineNumberingPushbackReader. (reader f))]
    (try
      (edn/read rdr)
      (catch Exception e
        (throw
         (ex-info (str "Could not read " (str f)
                       ":" (.getLineNumber rdr)
                       ":" (.getColumnNumber rdr)
                       ".  " (.getMessage e))
                  {:type :share911/environment
                   :reason :invalid-environment-file
                   :file (str f)
                   :line (.getLineNumber rdr)
                   :column (.getColumnNumber rdr)
                   :exit-code 1}
                  e))))))
