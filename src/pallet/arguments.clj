(ns pallet.arguments
  "Delayed arguments")

(defprotocol DelayedArgument
  "A protocol for passing arguments, with delayed evaluation."
  (evaluate [x]))

(extend-type
 Object
 DelayedArgument
 (evaluate [x] x))

(deftype DelayedFunction
  [f]
  DelayedArgument
  (evaluate [_] (f)))

(defn computed
  "Pass a function to be used to compute an argument at resource applicaiton time."
  [f]
  (DelayedFunction. f))

(defmacro delayed
  "Pass an argument to be evaluated at resource applicaiton time."
  [& body]
  `(DelayedFunction. (fn [] ~@body)))

(defn delayed-fn
  "Pass an argument to be evaluated at resource applicaiton time."
  [f]
  (DelayedFunction. f))
