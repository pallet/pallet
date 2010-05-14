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

(def heynote-url "http://orcloud-heynote.appspot.com/")
;;(def heynote-url "http://localhost:8080/")

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
    (user-preferences :id (response :user-id))))

(defn #^String slurp*
  [f]
  (with-open [r f]
      (let [sb (StringBuilder.)]
        (loop [c (.read r)]
          (if (neg? c)
            (str sb)
            (do (.append sb (char c))
                (logging/info (str sb) )
                (recur (.read r))))))))

(defn- read-response
  "Read heynote response."
  [http-agnt]
  (let [ response (json-read/read-json
                   (java.io.PushbackReader.
                    (java.io.InputStreamReader.
                     (agent/stream http-agnt))))]
    (process-response response)
    response))

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
       "There was a problem with the feedback system."))
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
  (let [response (send-msg
                  "item/new" "POST"
                  (merge (message-map) (apply hash-map options)))]
    (println (response :item))))

(defn items
  "Recieve feedback items"
  [& options]
  (let [response (send-msg
                  "items" "GET"
                  (merge (message-map) (apply hash-map options)))
        items (response :items [])]
    (doseq [item items]
      (println item))))

(defn add-comment
  "Recieve feedback items"
  [item & options]
  (let [response (send-msg
                  "comment" "POST"
                  (-> (message-map)
                      (merge (apply hash-map options))
                      (assoc :item-id (if (and (string? item)
                                               (.startsWith item "%"))
                                        (.substring item 1)
                                        item))))
        comment (response :item [])]
    (println comment)))
