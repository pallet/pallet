(ns pallet.argument
  "Arguments to resources.  Adds capability of evaluating arguments at
   resource application")

(defprotocol DelayedArgument
  "A protocol for passing arguments, with delayed evaluation."
  (evaluate [x request]))

;; By default, arguments should evaluate to themeselves
(extend-type
 Object
 DelayedArgument
 (evaluate [x request] x))

(deftype DelayedFunction
  [f]
  DelayedArgument
  (evaluate [_ request] (f request)))

(defn delayed-fn
  "Pass a function with a single argument, to be used to compute an argument at
   resource applicaiton time."
  [f]
  (DelayedFunction. f))

(defmacro delayed
  "Pass an argument to be evaluated at resource applicaiton time."
  [[request-sym] & body]
  `(DelayedFunction. (fn [~request-sym] ~@body)))
