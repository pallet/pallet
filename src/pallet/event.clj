(ns pallet.event
  "Pallet events. Provides ability to hook into pallet event stream."
  (:require
   [clojure.core.async :refer [>!! chan]]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]))

;; should be part of session
(defonce ^{:private true} publishers (atom {}))

(defn add-publisher
  "Add a publisher from the list notified by each call to publish."
  [kw publisher]
  (swap! publishers assoc kw publisher))

(defn remove-publisher
  "Remove a publisher from the list notified by each call to publish."
  [kw]
  (swap! publishers dissoc kw))

(defn remove-publishers
  "Remove all publisher from the list notified by each call to publish."
  []
  (reset! publishers {}))

(defn publish
  "Publish a pallet event."
  [m]
  (doseq [[_ publisher] @publishers]
    (publisher m)))

(defn log-publisher
  "An event publisher that logs to locally configured logger."
  [m]
  (logging/log
   (or (:ns m) *ns*)
   (:log-level m :debug)
   (:cause m)
   (str
    (:msg m)
    (when-let [kw-vals (seq (dissoc m :kw :msg :log-level :ns :ns :line))]
      (str
       " - "
       (string/join
        " " (map #(format "%s: %s" (first %) (second %)) kw-vals))))
    (when-let [ns (:ns m)] (format " [%s:%s]" ns (:line m))))))

(add-publisher :log #'log-publisher)

(defn async-publisher
  "Return a publisher for pushing events onto a channel"
  [channel]
  (fn [m]
    (>!! channel m)))

(defn session-event
  "Session event publisher"
  [event]
  (fn session-event-fn [session]
    [(publish event) session]))
