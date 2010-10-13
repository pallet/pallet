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
          (-> arg# ~form))))
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
