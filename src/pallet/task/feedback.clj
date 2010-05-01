(ns pallet.task.feedback
  "Send feedback to the pallet project.  Arguments will be sent as text."
  (:require
   [clojure.contrib.http.agent :as agent]
   [clojure.contrib.http.connection :as connection]
   pallet.compat))

(pallet.compat/require-contrib)

(def heynote-project "pallet")
(def heynote-url "http://orcloud-heynote.appspot.com/")

(defn feedback
  {:no-service-required true}
  [& args]
  (let [msg (apply str (interpose " " args))
        agent (agent/http-agent
               (str heynote-url "item/new")
               :method "POST"
               :body (json-write/json-str
                      {:project heynote-project
                       :text msg
                       :pallet-version (System/getProperty "pallet.version")
                       :java-version (System/getProperty "java.version")})
               :headers {"Content-type" "application/json"})]
    (agent/result agent)
    (println
     (if (agent/client-error? agent)
       "There was a problem sending your feedback."
       "Feedback sent."))))
