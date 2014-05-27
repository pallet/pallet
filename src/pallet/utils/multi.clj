(ns pallet.utils.multi
  "Generalised multi-methods.

Defines a defmulti and defmethod macros as something similar to the
core defmethod/defmulti macros, except dispatch is via a general
predicate rather than via isa?."
  (:refer-clojure :exclude [defmulti defmethod])
  (:require
   [clojure.string :as string]
   [pallet.exception :refer [compiler-exception]]))

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

(defn dispatch-predicate
  "Return a function, that will dispatch into a method map based on
  the method key matching a predicate function.  The predicate
  function is obtained by calling `dispatch-fn` with `args`.  A best
  match for multiple matches is picked using `selector`, which
  defaults to `first`.  If no match is found, then the method with
  the `default` key value is used."
  [dispatch-fn {:keys [default name selector]
                :or {default :default
                     selector first}}]
  {:pre [(fn? dispatch-fn)]}
  (fn dispatch-predicate [methods args]
    (let [pred (apply dispatch-fn args)
          method (->>
                  methods
                  (filter (comp pred key))
                  selector)
          f (or (and method (val method))
                (get methods default))]
      (if f
        (apply f args)
        (throw
         (ex-info (str "Dispatch failed "
                       (if name (str "in " name " ")))
                  {:args args}))))))

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
         (ex-info (str "Dispatch failed"
                       (if name (str " in " name))
                       " for arguments " (pr-str args))
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

(defmacro defmulti
  "Declare a multimethod with a predicate based dispatch function.
  The `dispatch-fn` argument must be a function returning a single
  argument predicate, which will be passed each method's
  `dispatch-val`.  `selector`, if passed, will be used to pick amongst
  multiple matches.  If no match is found using `dispatch-fn`, the the
  method with the `default` key value is used.  This does not have
  `defonce` semantics."
  {:arglists '[[name docstring? attr-map? dispatch-fn
                {:keys [default selector]}]]}
  [name & args]
  (let [{:keys [name dispatch options]} (name-with-attributes name args)
        {:keys [selector]} options
        args (first (filter vector? dispatch))
        f (gensym "f")
        m (gensym "m")]
    `(let [~f (dispatch-predicate ~dispatch (merge ~options {:name '~name}))
           ~m (dispatch-map ~f)]
       ~(with-meta
          `(defn ~name
             {::dispatch (atom ~m)
              :arglists '~[args]}
             [& [~@args :as argv#]]
             (check-arity ~name ~(count args) (count argv#))
             (~f (:methods @(-> #'~name meta ::dispatch)) argv#))
          (meta &form)))))

(defmacro defmethod
  "Declare a method for the `multifn` multi-method, associating it with the
  `dispatch-val` for dispatching via the multi-method's `dispatch-fn`."
  [multifn dispatch-val args & body]
  (letfn [(sanitise [v] (string/replace (str v) #":" ""))]
    (when-not (resolve multifn)
      (throw (compiler-exception
              &form (str "Could not find defmulti " multifn))))
    `(swap!
      (-> #'~multifn meta ::dispatch)
      add-method
      ~dispatch-val
      ~(with-meta
         `(fn [~@args] ~@body)
         (meta &form)))))
