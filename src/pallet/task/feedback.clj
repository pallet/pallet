(ns pallet.task.feedback
  "Feedback to the pallet project.
      add  - send new feedback. Arguments will be sent as text.
      list - show recent feedback."
  (:require
   [pallet.heynote :as heynote]
   [clojure.contrib.http.agent :as agent]
   [clojure.contrib.http.connection :as connection]
   pallet.compat))

(pallet.compat/require-contrib)

(def heynote-project (heynote/project "pallet"))

(def desc "feedback")
(defn as-keyword [x]
  (cond
   (string? x) (keyword x)
   (symbol? x) (keyword (name x))
   :else x))

(defn feedback
  {:no-service-required true}
  [& args]
  (let [[task & args] args
        task (as-keyword task)
        task (or task :list)]
    (condp = task
        :add  (heynote/new-item
               :text (apply str (interpose " " args)))
        :list (heynote/items)
        :show (if-let [item (first args)]
                (heynote/item item)
                (println "Specify the %tag to show."))
        :comment (let [[item & args] args]
                   (if item
                     (heynote/add-comment
                      item
                      :text (apply str (interpose " " args)))
                     (println "Specify the %tag to comment on.")))
        (do (println "Unknown feedback command" task)
            (println "Valid feedback commands:")
            (println "  list         - list feedback")
            (println "  add          - add a feedback (%tag to name it)")
            (println "  comment %tag - add a comment on the specified item")
            (println "  show %tag    - show the specified item")))))
