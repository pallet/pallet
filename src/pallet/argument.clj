(ns pallet.argument
  "Arguments to actions.  Adds capability of evaluating arguments at
   action application")

(def ^{:doc "Provides a session inside a delayed function. This should be
considered an implementation detail."
       :dynamic true}
  *session*)

(defprotocol DelayedArgument
  "A protocol for passing arguments, with delayed evaluation."
  (evaluate [x session]))

;; By default, arguments should evaluate to themselves
(extend-type
    Object
  DelayedArgument
  (evaluate [x session] x))

(extend-type
    clojure.lang.PersistentHashMap
  DelayedArgument
  (evaluate [x session]
    (into {} (map
              #(vector (key %) (when-let [v (val %)] (evaluate v session)))
              x))))

(extend-type
    clojure.lang.PersistentArrayMap
  DelayedArgument
  (evaluate [x session]
    (into {} (map
              #(vector (key %) (when-let [v (val %)] (evaluate v session)))
              x))))

(deftype DelayedFunction
    [f]
  DelayedArgument
  (evaluate [_ session]
    (binding [*session* session]
      (loop [v (f session)]
        (if (satisfies? DelayedArgument v)
          (let [v1 (evaluate v session)]
            (if (= v v1)
              v
              (recur v1)))
          v)))))

(defn delayed-fn
  "Pass a function with a single argument, to be used to compute an argument at
   action application time."
  [f]
  (DelayedFunction. f))

(defmacro delayed
  "Pass an argument to be evaluated at action application time."
  [[session-sym] & body]
  `(DelayedFunction. (fn [~session-sym] ~@body)))
