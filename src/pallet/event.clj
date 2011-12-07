(ns pallet.event
 "Pallet events. Provides ability to hook into pallet event stream."
 (:require
  [clojure.string :as string]
  [clojure.tools.logging :as logging]))

;; should be part of session
(defonce ^{:private true} publishers (atom #{}))

(defn add-publisher
  "Add a publisher from the list notified by each call to publish."
  [publisher]
  (swap! publishers conj publisher))

(defn remove-publisher
  "Remove a publisher from the list notified by each call to publish."
  [publisher]
  (swap! publishers disj publisher))

(defn remove-publishers
  "Remove all publisher from the list notified by each call to publish."
  []
  (reset! publishers #{}))

(defn publish
  "Publish a pallet event."
  [m]
  (doseq [publisher @publishers]
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

(add-publisher #'log-publisher)
