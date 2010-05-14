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


(defn as-number-if-possible [x]
  (if (string? x)
    (try
      (Long/parseLong x)
      (catch NumberFormatException e
        x))
    x))

(defn as-string
  [x]
  (cond
   (keyword? x) (name x)
   (symbol? x) (name x)
   :else (str x)))

(defn item-id-from-string
  [item]
  (as-number-if-possible
   (.substring (as-string item) 1)))

(defn item
  "Recieve feedback item"
  [item & options]
  (let [response (send-msg
                  "item" "GET"
                  (-> (message-map)
                      (merge (apply hash-map options))
                      (assoc :item-id (item-id-from-string item))))
        item (response :item)
        comments (response :comments [])]
    (println item)
    (doseq [comment comments]
      (println comment))))

(defn add-comment
  "Receive feedback items"
  [item & options]
  (let [response (send-msg
                  "comment" "POST"
                  (-> (message-map)
                      (merge (apply hash-map options))
                      (assoc :item-id (item-id-from-string item))))
        comment (response :item "There was a problem adding your comment.")]
    (println comment)))
