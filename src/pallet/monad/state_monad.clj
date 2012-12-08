(ns pallet.monad.state-monad
  "Provides a state monad implementation, based on clojure.algo.monads.

This aims to solve two problems that occur when using state monads with
clojure.algo.monads.  Firstly stack traces are unusable, since they contain only
bind calls (ie. no identifiable user code).  Secondly they use a lot of stack,
since each expression in a comprehension becomes a nested stack frame."
  (:require
   [clojure.string :as string]))

;;; # Monad Implementation

;;; These are macros so we avoid adding a frame to the stack, which makes it
;;; more readable.

;;; ## State Checking
(def ^:internal state-checker
  "This can be set to a function that is expected to check the state in some
way. The function can not modify the state used in any way."
  nil)

(defn check-state-with
  "Specify a global function that checks the state in some way. This function
  will be called at compile time, so can not be modified at run time. The
  function can not modify the state used in any way."
  [f] (alter-var-root #'state-checker (constantly f)))

;;; ## State `result` and `bind` Macros
(defn sanitise-for-symbol [s]
  (->
   (pr-str s)
   (string/replace #"[- :().\[\]+,#\"!]" "_")
   (string/replace #"clojure.core/" "")
   (string/replace #"_+" "_")
   (string/replace #"_[0-9]+_(auto_)?" "_G_")                  ; replace gensym
   (string/replace #"let_f_G_fn_wrap_arg[0-9]+_" "") ; wrap_arg
   (string/replace #"try_f_G_catch.*" "") ; wrap_arg
   (string/replace
    #"fn_session_G_get_components_session_G_quote_[a-z0-9]*_"
    "") ; anonymous function
   (string/replace #"fn\*_p1_G" "fn")))

(defmacro m-result [v]
  (let [fname (gensym "m-resultf")]
    `(fn ~fname [s#] [~v s#])))

;;; `m-bind` is a macro to reduce the stack depth, and to allow the bind
;;; function to be named for identification
(defn- read-property
  "Read a system property as a clojure value."
  [property-name]
  (when-let [property (System/getProperty property-name)]
    (if (string? property)
      (read-string property)
      property)))

(def use-long-fn-names (read-property "pallet.long-fn-names"))

(defmacro mfn*
  [[argv] body & args]
  (let [[argv] args]
    body))

(defmacro mfn
  "An macro form which looks like an anonymous function, and can be used to
  inline a function call "
  [args body & arg-vals]
  `(let [~args ~(vec arg-vals)]
     ~body))

(defmacro mapply
  [f & args]
  (if (and (sequential? f) (or (= `mfn (first f))  (= 'mfn (first f))))
    `~(concat f args)
    `(~f ~@args)))

;; (mapply (mfn [x] x) 1)
;; (mapply (mfn [x] (+ x 2)) 1)
;; (mapply (fn [x] (+ x 3)) 1)
;; (mapply inc 1 )

(defn m-bind [mv f]
  (fn fname [s]
    (when state-checker
      (state-checker s {:f f}))
    (let [[v ss] (mv s)]
      (when state-checker
        (state-checker ss {:f f}))
      ((f v) ss))))

;; (defmacro m-bind [mv f]
;;   (let [s (gensym "state")
;;         ss (gensym "state")
;;         v (and (symbol? f) (not (get &env f)) (resolve f))
;;         n (and v (-> v meta :name))
;;         form (or (-> mv meta :form) mv)
;;         fname (gensym (or
;;                        n
;;                        (when use-long-fn-names
;;                          (let [s (sanitise-for-symbol form)]
;;                            (str "bfn" (subs s 0 (min 50 (count s))))))
;;                        "m-bind-fn"))]
;;     (with-meta
;;       `(fn ~fname [~s]
;;          ~@(when state-checker `[(when state-checker (state-checker ~s '~f))])
;;          (let [[v# ~ss] (~mv ~s)]
;;            ~@(when state-checker
;;                `[(when state-checker (state-checker ~ss '~f))])
;;            (mapply (mapply ~f v#) ~ss)))
;;       (select-keys (meta mv) [:line]))))

;;; # Monadic Comprehension
(defn- ensure-items [n steps]
  "Ensures there are at least n elements on a list, will fill up with nil
  values when list is not big enough."
  (take n (concat steps (repeat nil))))

(defn- each3-steps [steps]
  "Transforms a list in a list of triples following the form:
   [a b c] => [[a b c] [b c nil] [c nil nil]]."
  (let [n (count steps)]
  (map vector (ensure-items n steps)
              (ensure-items n (rest steps))
              (ensure-items n (rest (rest steps))))))

(def ^:private prepare-monadic-steps
     #(->> % (partition 2) reverse each3-steps))

(defn- if-then-else-statement
  "Process an :if :then :else steps when adding a new
  monadic step to the mexrp."
  [[[_          else-mexpr]
    [then-bform then-mexpr]
    [if-bform   if-conditional]] mexpr continuation]
    (cond
      (and (identical? then-bform :then)
           (identical? if-bform   :if))
        `(if ~if-conditional
          ~(reduce continuation
                   mexpr
                   (prepare-monadic-steps then-mexpr))
          ~(reduce continuation
                   mexpr
                   (prepare-monadic-steps else-mexpr)))
      :else
       (throw (Exception. "invalid :if without :then and :else"))))

(defn- merge-cond-branches [cond-branches]
  (let [merger (fn [result cond-branch]
                  (-> result
                      (conj (first cond-branch))
                      (conj (second cond-branch))))]
    (reduce merger [] cond-branches)))

(defn cond-statement
  "Process a :cond steps when adding a new monadic step to the mexrp."
  [expr mexpr continuation]
  (let [cond-sexps (partition 2 expr)
        result (for [[cond-sexp monadic-sexp] cond-sexps]
                     (list cond-sexp
                           (reduce continuation
                                   mexpr
                                   (prepare-monadic-steps monadic-sexp))))]
      `(cond ~@(merge-cond-branches result))))

(defn- add-monad-step
  "Add a monad comprehension step before the already transformed
   monad comprehension expression mexpr."
  [mexpr steps]
  (let [[[bform expr :as step] & _] steps]
    (with-meta
      (cond
       (identical? bform :when)  `(if ~expr ~mexpr ~'m-zero)
       (identical? bform :let)   `(let ~expr ~mexpr)
       (identical? bform :cond)  (cond-statement expr mexpr add-monad-step)
       (identical? bform :then)  mexpr
                                        ; ^ ignore :then step (processed on the :else step)
       (identical? bform :if)    mexpr
                                        ; ^ ignore :if step (processed on the :else step)
       (identical? bform :else)
       (if-then-else-statement steps mexpr add-monad-step)
       :else
       (list `m-bind expr (list 'fn [bform] mexpr)))
      (select-keys (meta steps) [:line]))))

(defn- monad-expr
  "Transforms a monad comprehension, consisting of a list of steps
   and an expression defining the final value, into an expression
   chaining together the steps using :bind and returning the final value
   using :result. The steps are given as a vector of
   binding-variable/monadic-expression pairs."
  [steps expr]
  (when (odd? (count steps))
    (throw (Exception. "Odd number of elements in monad comprehension steps")))
  (let [rsteps (prepare-monadic-steps steps)]
    (reduce add-monad-step (list `m-result expr) rsteps)))

(defmacro dostate
  "A comprehension for the state monad"
  [bindings result]
  (monad-expr bindings result))

;;; # Monad Functions
(defmacro m-lift
  "Converts a function f of n arguments into a function of n
  monadic arguments returning a monadic value."
  [n f]
  (let [expr (take n (repeatedly #(gensym "x_")))
        vars (vec (take n (repeatedly #(gensym "mv_"))))
        steps (vec (interleave expr vars))]
    (list `fn vars (monad-expr steps (cons f expr)))))

(defn m-join
  "Converts a monadic value containing a monadic value into a 'simple'
   monadic value."
  [m]
  (m-bind m identity))

(defn m-fmap
  "Bind the monadic value m to the function returning (f x) for argument x"
  [f m]
  (m-bind m (fn [x] (m-result (f x)))))

(defn m-seq
  "'Executes' the monadic values in ms and returns a sequence of the
   basic values contained in them."
  [ms]
  (reduce (fn [q p]
            (m-bind p (fn [x]
                        (m-bind q (fn [y]
                                    (m-result (cons x y)))) )))
          (m-result '())
          (reverse ms)))

(defn m-map
  "'Executes' the sequence of monadic values resulting from mapping
   f onto the values xs. f must return a monadic value."
  [f xs]
  (m-seq (map f xs)))

(defn m-chain
  "Chains together monadic computation steps that are each functions
   of one parameter. Each step is called with the result of the previous
   step as its argument. (m-chain (step1 step2)) is equivalent to
   (fn [x] (domonad [r1 (step1 x) r2 (step2 r1)] r2))."
  [steps]
  (reduce (fn m-chain-link [chain-expr step]
            (fn [v] (m-bind (chain-expr v) step)))
          (fn [x] (m-result x))
          steps))

;; (defn m-reduce
;;   "Return the reduction of (m-lift 2 f) over the list of monadic values mvs
;;    with initial value (m-result val)."
;;   ([f mvs]
;;    (if (empty? mvs)
;;      (m-result (f))
;;      (let [m-f (m-lift 2 f)]
;;        (reduce m-f mvs))))
;;   ([f val mvs]
;;    (let [m-f    (m-lift 2 f)
;;          m-val  (m-result val)]
;;      (reduce m-f m-val mvs))))

;; (defn m-until
;;   "While (p x) is false, replace x by the value returned by the
;;    monadic computation (f x). Return (m-result x) for the first
;;    x for which (p x) is true."
;;   [p f x]
;;   (if (p x)
;;     (m-result x)
;;     (domonad
;;       [y (f x)
;;        z (m-until p f y)]
;;       z)))

(defmacro m-when
  "If test is logical true, return monadic value m-expr, else return
   (m-result nil)."
  [test m-expr]
  `(if ~test ~m-expr (m-result nil)))

(defmacro m-when-not
  "If test if logical false, return monadic value m-expr, else return
   (m-result nil)."
  [test m-expr]
  `(if ~test (m-result nil) ~m-expr))
