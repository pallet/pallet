(ns pallet.compat
 "Enabling clojure 1.1/1.2 compatibility within pallet.")

(defn require-contrib
  []
  (try
   (require '(clojure.contrib
              [io :as io]
              [seq :as seq]
              [string :as string]
              [shell :as shell]
              [json :as json-write]
              [json :as json-read]))
   (use '(clojure.contrib [io :only [file] :rename {file -file}]))
   (catch Throwable e
     (require '(clojure.contrib
                [duck-streams :as io]
                [seq-utils :as seq]
                [str-utils2 :as string]
                [shell-out :as shell])
              '[clojure.contrib.json.write :as json-write]
              '[clojure.contrib.json.read :as json-read])
     (use '(clojure.contrib [java-utils :only [file] :rename {file -file}])))))

(require-contrib)

(def file -file)
