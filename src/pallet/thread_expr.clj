(ns pallet.thread-expr
  "Macros that can be used in an expression thread")

(defmacro for->
  "Apply a thread expression to a sequence.
   eg.
      (-> 1
        (for-> [x [1 2 3]]
          (+ x)))
   => 7"
  [arg [value s & {:keys [let]}] & body]
  (clojure.core/let
   [argsym (gensym "arg")]
   `(let [arg# ~arg s# ~s] ; maintain correct evaluation order
      (reduce
       (fn [~argsym ~value]
         ~(if let
            `(let ~let
               (-> ~argsym ~@body))
            `(-> ~argsym ~@body)))
       arg#
       s#))))

(defmacro when->
  "A `when` form that can appear in a expression thread.
   eg.
      (-> 1
        (when-> true
          (+ 1)))
   => 2"
  [arg condition & body]
  `(let [arg# ~arg]
     (if ~condition
       (-> arg# ~@body)
       arg#)))

(defmacro when-not->
  "A `when-not` form that can appear in a expression thread.
   eg.
      (-> 1
        (when-not-> true
          (+ 1)))
   => 2"
  [arg condition & body]
  `(let [arg# ~arg]
     (if-not ~condition
       (-> arg# ~@body)
       arg#)))

(defmacro when-let->
  "A `when-let` form that can appear in a expression thread.
   eg.
      (-> 1
        (when-let-> [a 1]
          (+ a)))
   => 2"
  [arg binding & body]
  `(let [arg# ~arg]
     (if-let ~binding
       (-> arg# ~@body)
       arg#)))

(defmacro if->
  "An `if` form that can appear in a expression thread
   eg.
      (-> 1
        (if-> true
          (+ 1)
          (+ 2)))
   => 2"
  ([arg condition form]
     `(let [arg# ~arg]
        (if ~condition
          (-> arg# ~form)
          arg#)))
  ([arg condition form else-form ]
     `(let [arg# ~arg]
        (if ~condition
          (-> arg# ~form)
          (-> arg# ~else-form)))))

(defmacro if-not->
  "An `if-not` form that can appear in a expression thread
   eg.
      (-> 1
        (if-not-> true
          (+ 1)
          (+ 2)))
   => 3"
  ([arg condition form]
     `(let [arg# ~arg]
        (if-not ~condition
          (-> arg# ~form))))
  ([arg condition form else-form ]
     `(let [arg# ~arg]
        (if-not ~condition
          (-> arg# ~form)
          (-> arg# ~else-form)))))

(defmacro let->
  "A `let` form that allows for binding of variables in a threading
  expression, with support for destructuring. For example:

 (-> 5
    (let-> [x 10]
           (for-> [y (range 4)
                   z (range 2)] (+ x y z))))
 ;=> 101"
  [arg letvec & body]
  `(let ~letvec (-> ~arg ~@body)))

(defmacro let-with-arg->
  "A `let` form that can appear in a request thread, and assign the
   value of the threaded arg. For example:

    (-> 1
      (let-with-arg-> val [a 1]
        (+ a val)))
  ;=> 3"
  [arg arg-symbol binding & body]
  `(let [~arg-symbol ~arg
         ~@binding]
     (-> ~arg-symbol ~@body)))

(defmacro apply->
  "Apply in a threaded expression.
   e.g.
      (-> 1
        (apply-> + [1 2 3]))
   => 7"
  [request f & args]
  `(let [request# ~request]
     (apply ~f request# ~@args)))

(defmacro apply-map->
  "Apply in a threaded expression.
   e.g.
      (-> :a
        (apply-map-> hash-map 1 {:b 2}))
   => {:a 1 :b 2}"
  [request f & args]
  `(let [request# ~request]
     (apply ~f request# ~@(butlast args) (apply concat ~(last args)))))
