(ns pallet.core.thread-local
  "Provides a thread local place, using a dynamic var.

A thread local place can be given a value using `with-thread-locals`.  It is an
error to use `with-thread-locals` if the thread local already has a value.

The current value of the thread local is found with `thread-local`.

The current value of the thread local can be set with `thread-local!`.  It is an
error to set the value on a thread other than the one used to provide the
initial value using `with-thread-locals`.

     (def ^:dynamic *tl*)

     (with-thread-locals [*t1* {}]
      (assert (= (thread-local *tl*) {}))
      (thread-local! *tl* {:a 1})
      (assert (= (thread-local *tl*) {:a 1})))"
  (:require
   [clojure.core.typed :refer [ann tc-ignore Atom1]]
   [pallet.core.types :refer [Session]]))

;;; We need a dynamic var to provide a thread local place. Var's do not provide
;;; a way to set the thread local value however, so we use an atom in a dynamic
;;; var.  Use of an atom is overkill - we just need a mutable place.  An
;;; alternative would be to use a deftype to provide the mutable place.

(defmacro with-thread-locals
  "Assign an initial value to a thread local."
  [bindings & body]
  (assert (vector bindings) "with-thread-locals bindings should be a vector")
  (let [bindings (partition 2 bindings)
        thread-id (gensym "thread")]
    `(let [~@(when *assert* `[~thread-id (.getId (Thread/currentThread))])]
       ;; TODO - remove tc-ignore when every? is smarter
       (assert (tc-ignore
                (every? (complement bound?)
                        ~(vec (map #(list `var (first %)) bindings)))))
       (binding
           ~(vec (mapcat
                  #(list
                    (first %)
                    `(atom ~(second %)
                           ~@(when *assert* `[:meta {:thread-id ~thread-id}])))
                  bindings))
         ~@body))))

(ann thread-local (All [x] [(clojure.lang.IDeref x) -> x]))
(defn thread-local
  "Get the value of the thread local"
  [sym]
  (deref sym))

(ann thread-local! (All [x] [(Atom1 x) x -> x]))
(defn thread-local!
  "Reset the value of a thread local"
  [sym value]
  (assert (= (-> sym meta :thread-id) (.getId (Thread/currentThread)))
          "Attempting to set thread local from incorrect thread.")
  (reset! sym value))
