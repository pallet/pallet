(ns pallet.task.feedback
  "Send feedback to the pallet project.  Arguments will be sent as text."
  (:require
   [pallet.heynote :as heynote]
   [clojure.contrib.http.agent :as agent]
   [clojure.contrib.http.connection :as connection]
   pallet.compat))

(pallet.compat/require-contrib)

(def heynote-project (heynote/project "pallet"))

(defn feedback
  {:no-service-required true}
  [& args]
  (heynote/new-item
   :text (apply str (interpose " " args))))
