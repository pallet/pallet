(ns pallet.utils.multi
  "Multi-method implementation details")

(defn dispatch-map
  "Return a dispatch map.  The map contains :dispatch-fn and :methods
  keys. The dispatch-f must be a function that will be called with the
  the methods as the first argument, and the arguments to dispatch as
  the second."
  [dispatch-f]
  {:dispatch-fn dispatch-f
   :methods {}})

(defn add-method
  "Adds a dispatch key and function to a dispatch map."
  [dispatch-map dispatch-val f & disp-val-fns]
  (apply update-in dispatch-map [:methods] assoc dispatch-val f disp-val-fns))

(defn dispatch-key-fn
  "Return a function, that will dispatch into a method map based on
  the value returned by the key-fn."
  [key-fn {:keys [default hierarchy name] :or {default :default}}]
  (fn dispatch-key-fn [methods args]
    (let [value (apply key-fn args)
          method (or (get methods value)
                     (if (or (symbol? value) (keyword? value))
                       (some (fn [[k f]]
                               (if (and (or (symbol? k) (keyword? k))
                                        (if hierarchy
                                          (isa? hierarchy value k)
                                          (isa? value k)))
                                 f))
                             methods))
                     (get methods default))]
      (if method
        (apply method args)
        (throw
         (ex-info (str "Dispatch failed "
                       (if name (str "in " name " "))
                       "for dispatch value " value)
                  {:args args
                   :value value}))))))

(defn dispatch-every-fn
  "Return a function, that will dispatch into a method map based on
the method key matching a sequence of predicate functions.  Matches
may be sorted to pick the best match."
  [match-fns {:keys [default name selector]
              :or {default :default
                   selector first}}]
  {:pre [(every? fn? match-fns)]}
  (fn dispatch-every-fn [methods args]
    (let [method (->>
                  methods
                  (filter (fn [method]
                            (every? #(% (key method) args) match-fns)))
                  selector)
          f (or (and method (val method))
                (get methods default))]
      (if f
        (apply f args)
        (throw
         (ex-info (str "Dispatch failed "
                       (if name (str "in " name " ")))
                  {:args args}))))))

;;; Anonymous multi-methods
(defn- multi-fn*
  "Return an anonymous multimethod function.
  Add methods using assoc-method!."
  ([dispatch-fn {:keys [name selector] :as options}]
     (let [a (atom (dispatch-map dispatch-fn))]
       ^{::dispatch a}
       (fn [& argv]
         (dispatch-fn (:methods @a) argv)))))

(defn multi-fn
  "Return an anonymous multimethod function.
  Add methods using assoc-method!."
  ([dispatch-f {:keys [name hierarchy] :as options}]
     (multi-fn* (dispatch-key-fn dispatch-f options) options))
  ([dispatch-f]
     (multi-fn dispatch-f {})))

(defn multi-every-fn
  "Return an anonymous multimethod function.
  Add methods using assoc-method!."
  ([dispatch-fns {:keys [name selector] :as options}]
     {:pre [(every? fn? dispatch-fns)]}
     (multi-fn* (dispatch-every-fn dispatch-fns options) options))
  ([dispatch-fns]
     (multi-every-fn dispatch-fns {})))

(defn assoc-method!
  "Add a method to multi-fn, so dispatch-val will call f.
  Mutates the multi-fn containing the method."
  [multi-fn dispatch-val f & disp-val-fns]
  {:pre [(fn? f)]}
  (let [dispatch (-> multi-fn meta ::dispatch)]
    (when-not dispatch
      (throw (ex-info "Trying to assoc-method on non multi-fn" {})))
    (apply swap! dispatch add-method dispatch-val f disp-val-fns)))


;;; # def macro writing functions and macros
(defn name-with-attributes
  "For writing defmulti like macros. Handles optional docstrings and
  attribute maps for a name to be defined in a list of macro
  arguments.

  name docstring? attr-map? dispatch options-map? "
  [name args]
  (let [[docstring args] (if (string? (first args))
                           [(first args) (rest args)]
                           [nil args])
        [attr-map args] (if (map? (first args))
                          [(first args) (rest args)]
                          [nil args])
        dispatch (first args)
        options (fnext args)
        attr-map (if docstring (assoc attr-map :doc docstring) attr-map)]
    {:name (with-meta name attr-map)
     :dispatch dispatch
     :options options}))

(defmacro check-arity
  "Macro to throw on arity error."
  [name expected actual]
  `(let [expected# ~expected
         actual# ~actual]
     (when-not (= expected# actual#)
       (throw
        (clojure.lang.ArityException.
         actual# ~(clojure.core/name name))))))
