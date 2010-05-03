(ns pallet.heynote
  "Heynote feedback client"
  (:require
   pallet.compat
   [clojure.contrib.logging :as logging]
   [clojure.contrib.http.agent :as agent]
   [clojure.contrib.http.connection :as connection]))

(pallet.compat/require-contrib)

(defonce heynote-project (atom nil))

(defn project
  "Sets and returns the heynote project name"
  [name]
  (reset! heynote-project name))

;;(def heynote-url "http://orcloud-heynote.appspot.com/")
(def heynote-url "http://localhost:8080/")

(def user-prefs (atom nil))

(def user-prefs-file
     (str (. System getProperty "user.home") "/.heynote"))

(defn- user-preferences
  [& options]
  (when-not @user-prefs
    (try
     (reset! user-prefs (read-string (slurp user-prefs-file)))
     (catch Exception e
       (reset! user-prefs {}))))
  (when (seq options)
    (swap! user-prefs merge (apply hash-map options))
    (io/spit user-prefs-file (with-out-str (pr @user-prefs))))
  @user-prefs)

(defn- process-response
  [response]
  (when (nil? ((user-preferences) :id))
    (user-preferences :id (response "user-id"))))

(defn- read-response
  "Read heynote response."
  [http-agnt]
  (let [reader (java.io.PushbackReader.
                      (java.io.InputStreamReader.
                       (agent/stream http-agnt)))]
    (let [response (json-read/read-json reader)]
      (process-response response)
      response)))

(defn send-msg
  "Send the given message map."
  [path method msg-map]
  (let [agent (agent/http-agent
               (str heynote-url path)
               :method method
               :body (json-write/json-str msg-map)
               :headers {"Content-type" "application/json"}
               :handler read-response)
        response (agent/result agent)]
    (println
     (if (agent/client-error? agent)
       "There was a problem sending your feedback."
       "Feedback sent."))
    response))

(defn message-map
  "Return a map that can be used to send to heynote"
  []
  (when-not @heynote-project
    (throw (java.lang.RuntimeException. "No heynote project configured")))
  {:project @heynote-project
   :user-id (:id (user-preferences))})

(defn new-item
  "Send new feedback with the given message"
  [& options]
  (send-msg "item/new" "POST" (merge (message-map) (apply hash-map options))))
