(ns pallet.argument
  "Arguments to actions.  Adds capability of evaluating arguments at
   action application")

(defprotocol DelayedArgument
  "A protocol for passing arguments, with delayed evaluation."
  (evaluate [x session]))

;; By default, arguments should evaluate to themeselves
(extend-type
 Object
 DelayedArgument
 (evaluate [x session] x))

(extend-type
 clojure.lang.PersistentHashMap
 DelayedArgument
 (evaluate [x session]
   (into {} (map #(vector (key %) (evaluate (val %) session)) x))))

(extend-type
 clojure.lang.PersistentArrayMap
 DelayedArgument
 (evaluate [x session]
   (into {} (map #(vector (key %) (evaluate (val %) session)) x))))

(deftype DelayedFunction
  [f]
  DelayedArgument
  (evaluate [_ session] (f session)))

(defn delayed-fn
  "Pass a function with a single argument, to be used to compute an argument at
   action application time."
  [f]
  (DelayedFunction. f))

(defmacro delayed
  "Pass an argument to be evaluated at action application time."
  [[session-sym] & body]
  `(DelayedFunction. (fn [~session-sym] ~@body)))
