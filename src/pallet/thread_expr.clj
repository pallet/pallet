(ns pallet.thread-expr
  "Macros that can be used in an expression thread")

(defmacro for->
  "Apply a thread expression to a sequence.
   eg.
      (-> 1
        (for-> [x [1 2 3]]
          (+ x)))
   => 7"
  [arg seq-exprs & body]
  `((apply comp (conj
                 (reverse
                  (for ~seq-exprs
                    (fn [arg#] (-> arg# ~@body))))
                 identity))
    ~arg))

(defmacro when->
  "A `when` form that can appear in a request thread.
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
  "A `when-not` form that can appear in a request thread.
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
  "A `when-let` form that can appear in a request thread.
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
  "An `if` form that can appear in a request thread
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
  "An `if-not` form that can appear in a request thread
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


(defmacro arg->
  "Lexically assign the threaded argument to the specified symbol.

       (-> 1
         (arg-> [x] (+ x)))

       => 2"
  [arg [sym] & body]
  `(let [~sym ~arg]
     (-> ~sym ~@body)))

(defmacro let-with-arg->
  "A `let` form that can appear in a request thread, and assign the value of the
   threaded arg.
   eg.
      (-> 1
        (let-with-arg-> val [a 1]
          (+ a val)))
   => 3"
  [arg arg-symbol binding & body]
  `(let [~arg-symbol ~arg]
     (let ~binding
       (-> ~arg-symbol ~@body))))

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
