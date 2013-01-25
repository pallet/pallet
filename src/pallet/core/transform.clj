(ns pallet.core.transform
  "Plan function code transformation.

We plan functions to meet most peoples expectations of how clojure functions
should work.  In order for this to happen, we need to hide the difference
between action plan building, and action plan execution.

node-values are placeholders for execution time values.

- If a plan function uses a node-value in an expression, then that expression
  needs to be evaluated at runtime.

- If the expression is used to control a conditional branch, then the
  conditional needs to be rewritten to us the plan conditional macros.

This is complicated by the fact that node-values can be passed as arguments to
plan functions.  This means that the distinction may have to occur at runtime,
or that we assume arguments are not node-values, and require the user to adjust
if they are (is this advanced use?).
"
  (:require
   [analyze.core :as analyze
    :refer [analyze-form analyze-form-in-ns analyze-one ast]]
   [analyze.emit-form :refer [map->form]])
  (:import [clojure.lang RT Compiler]))

(defmacro ^:private thrd-bindings [source-path source-nsym line]
  `(merge
     {Compiler/LOADER (RT/makeClassLoader)
      Compiler/SOURCE_PATH (str ~source-path)
      Compiler/SOURCE (str ~source-nsym)
      Compiler/METHOD nil
      Compiler/LOCAL_ENV nil
      Compiler/LOOP_LOCALS nil
      Compiler/NEXT_LOCAL_NUM 0
      RT/CURRENT_NS @RT/CURRENT_NS
      ;; Compiler/LINE_BEFORE (int ~line)
      ;; Compiler/LINE_AFTER (int ~line)
      RT/UNCHECKED_MATH @RT/UNCHECKED_MATH}
     ;; ~(when (RT-members 'WARN_ON_REFLECTION)
     ;;    `{(field RT ~'WARN_ON_REFLECTION) @(field RT ~'WARN_ON_REFLECTION)})
     ;; ~(when (Compiler-members 'COLUMN_BEFORE)
     ;;    `{Compiler/COLUMN_BEFORE (.getColumnNumber ~pushback-reader)})
     ;; ~(when (Compiler-members 'COLUMN_AFTER)
     ;;    `{Compiler/COLUMN_AFTER (.getColumnNumber ~pushback-reader)})
     ;; ~(when (RT-members 'DATA_READERS)
     ;;    `{RT/DATA_READERS @RT/DATA_READERS})
     ))

(defn plan-fn-ast
  [nsym form]
  (push-thread-bindings (thrd-bindings *file* nsym (or (-> form meta :line) 1)))
  (try
    (analyze-form-in-ns nsym form)
    (finally (pop-thread-bindings))))

;; this is missing in analyze.emit-form
(defmethod map->form :the-var
  [{:keys [var]}]
  (let [m (meta var)]
    (symbol (name (ns-name (:ns m))) (name (:name m)))))

(defn output
  "Output forms for an ast"
  [ast]
  (map->form ast))


;;; ## Node structure
(def ast-node-structure
  {:nil {:fields [:val]}
   :number {:fields [:val]}
   :constant {:fields [:val]}
   :string {:fields [:val]}
   :boolean {:fields [:val]}
   :keyword {:fields [:val]}
   :static-method {:fields [:class :method-name] :child-seqs [:args]}
   :static-field {:fields [:class :field-name]}
   :invoke {:children [:fexpr] :child-seqs [:args]}
   :var {:fields [:var]}
   :the-var {:fields [:var]}
   :instance-method {:fields [:method-name]
                     :children [:target]
                     :child-seqs [:args]}
   :instance-field {:fields [:field-name]
                    :children [:target]}
   :new {:fields [:class] :child-seqs [:args]}
   :empty-expr {:fields [:coll]}
   :vector {:child-seqs [:args]}
   :map {:child-seqs [:keyvals]}
   :set {:child-seqs [:keys]}
   :fn-expr {:children [:variadic-method] :child-seqs [:methods]}
   :fn-method {:children [:body :rest-param] :child-seqs [:required-params]}
   :do {:child-seqs [:exprs]}
   :let {:fields [:is-loop] :children [:body] :child-seqs [:binding-inits]}
   :recur {:child-seqs [:args]}
   :binding-init {:children [:local-binding :init]}
   :local-binding {:fields [:sym]}
   :local-binding-expr {:children [:local-binding]}
   :if {:children [:test :then :else]}
   :case* {:children [:the-expr :default] :child-seqs [:tests :thens]}
   :instance-of {:fields [:class] :children [:the-expr]}
   :def {:fields [:var :init-provided] :children [:init]}
   :deftype* {:fields [:name] :child-seqs [:methods]}
   :new-instance-method {:fields [:name] :children [:body]
                         :child-seqs [:required-params]}
   :import* {:fields [:class-str]}
   :keyword-invoke {:children [:kw :target]}
   :throw {:children [:exception]}
   :try {:children [:try-expr :finally-expr] :child-seqs [:catch-exprs]}
   :catch {:fields [:class] :children [:local-binding :handler]}})

;;; ## Generic transformation of the ast.

;;; Each traversal function returns an updated ast, and a map for updating the
;;; parent node in the ast.

(def ^:dynamic *traversal-fn*)

(defmacro current-line [] (:line (meta &form)))

(defn deep-merge
  "Recursively merge maps."
  [& ms]
  (letfn [(f [a b]
            (if (and (map? a) (map? b))
              (deep-merge a b)
              b))]
    (apply merge-with f ms)))

;;; ## Child Update Functions

;;; ### Simple
(defn update-node [ast-node kw f]
  (if-let [v (get ast-node kw)] ; e.g. :variadic-method is optional in :fn-expr
    (let [[child parent] (f v)]
      (-> ast-node
          (assoc kw child)
          (deep-merge parent)))
    ast-node))

(defn update-node-seq
  "Update a node for the traversal of a child key containing a sequence."
  [ast-node kw f]
  (assert (get ast-node kw)
          (str "couldn't find " kw " in " ast-node))
  (reduce
   (fn [node sub-node]
     (let [[child parent] (f sub-node)]
       (-> node
           (update-in [kw] conj child)
           (deep-merge parent))))
   (assoc ast-node kw [])
   (get ast-node kw)))

;;; ### With Push-down of Keys
(defn update-node-with-pushdown
  "Update a node for the traversal of a child key containing a single node."
  [ast-node kw f  pd-keys]
  (if-let [v (get ast-node kw)] ; e.g. :variadic-method is optional in :fn-expr
    (let [[child parent] (f (merge v (select-keys ast-node pd-keys)))]
      (-> ast-node
          (assoc kw child)
          (deep-merge parent)))
    ast-node))

(defn update-node-seq-with-pushdown
  "Update a node for the traversal of a child key containing a sequence."
  [ast-node kw f pd-keys]
  (assert (get ast-node kw)
          (str "couldn't find " kw " in " ast-node))
  (reduce
   (fn [node sub-node]
     (let [[child parent] (f (merge sub-node (select-keys ast-node pd-keys)))]
       (-> node
           (update-in [kw] conj child)
           (deep-merge parent))))
   (assoc ast-node kw [])
   (get ast-node kw)))

;;; ## Traversal
;;; No push-down of keys, and no defined order for child traversal
(defmulti traverse-impl
  "Provides a traversal of the ast. Can be used as a default in a
transformation."
  :op)

(defn implement-traversal-node [[kw {:keys [children child-seqs]}]]
  `(defmethod traverse-impl ~kw
     [ast-node#]
     [(-> ast-node#
          ~@(map #(do`(update-node ~% *traversal-fn*)) children)
          ~@(map #(do`(update-node-seq ~% *traversal-fn*)) child-seqs))
      nil]))

(defmacro implement-traversal
  [] `(do ~@(map implement-traversal-node ast-node-structure)))

(implement-traversal)

;;; ## Ordered Traversal
;;; With push-down of keys, and no defined order for child traversal

(defn implement-traversal-node-with-pushdown
  "Create a traversal function that has a deterministic order of child visits,
  and pushes the selected keys down into the children."
  [name kw key-seq pd-keys]
  (let [struct (get ast-node-structure kw)
        children (set (:children struct))
        child-seqs (set (:child-seqs struct))]
    `(defmethod ~name ~kw
       [ast-node#]
       ;; (println "op" ~kw) (flush)
       ;; (println "pd-keys" ~pd-keys) (flush)
       ;; (println "children" ~children) (flush)
       ;; (println "child-seqs" ~child-seqs) (flush)
       ;; (println "key-seq" ~(vec key-seq)) (flush)
       ;; (println "xxx") (flush)
       [(-> ast-node#
            ~@(map
               #(cond
                 (children %)
                 `(update-node-with-pushdown ~% *traversal-fn* ~pd-keys)

                 (child-seqs %)
                 `(update-node-seq-with-pushdown ~% *traversal-fn* ~pd-keys)

                 :else (assert nil (str "Trying invalid key " % " for op " kw)))
               key-seq))
        nil])))

(defmacro implement-traversal-with-pushdown
  [name key-sequence-map pd-keys]
  (letfn [(default-keys [op]
            (apply concat
                   (-> (get ast-node-structure op)
                       (select-keys [:children :child-seqs])
                       vals)))
          (key-sequence [op]
            (let [ks (or (get key-sequence-map op) (default-keys op))]
              (assert
               (every?
                (set (apply concat (vals (get ast-node-structure op))))
                ks)
               (str "Trying to use non-existing key for op " op))
              ks))]
    `(do
       (defmulti ~name :op)
       ~@(map
          #(implement-traversal-node-with-pushdown
             name %1 (key-sequence %1) pd-keys)
          (keys ast-node-structure)))))

(implement-traversal-with-pushdown
  plan-traversal
  {:let [:binding-inits :body]}
  #{:pallet/local-env})

;;; The multi function is responsible for setting the scoped state seen by the
;;; transformation. The :default implementation should return a vector of the
;;; input arguments. The pre methods should return a vector containing the
;;; global and scoped states.

;;; plan-fn transformation
(defmulti plan-transform-impl
  "Updates the ast with plan rewrite information."
  :op)

;; default nodes to simple traversal
(defmethod plan-transform-impl :default
  [ast]
  (plan-traversal ast))

;; local bindings should propagate up to the containing binding form, so
;; we can build a symbol table
(defmethod plan-transform-impl :binding-init
  [ast-node]
  (let [[{:keys [local-binding init] :as node} parent]
        (plan-traversal ast-node)]
    [node
     (assoc parent :pallet/local-env
            {(:sym local-binding) (select-keys node [:pallet/node-value])})]))

;; Using a local binding should propagate `:pallet/node-value`
(defmethod plan-transform-impl :local-binding
  [{:keys [sym] :as ast-node}]
  [ast-node
   (when (get-in ast-node [:pallet/local-env sym :pallet/node-value])
     {:pallet/node-value true})])

(defmethod plan-transform-impl :invoke
  [ast-node]
  ;; do default traversal
  (let [[{:keys [fexpr] :as node} parent] (plan-traversal ast-node)
        props (when-let [v (:var fexpr)]
                (when-let [pf (-> v meta :pallet/plan-fn)]
                  {:pallet/plan-fn pf}))]
    ;; annotate with pallet info
    [(-> node
         (update-in [:fexpr] merge props))
     (merge parent {:pallet/node-value (:pallet/plan-fn props)})]))


(defn transform
  "Transform the given ast using the specified multi-methods and initial state."
  [ast multi]
  (binding [*traversal-fn* multi]
    (multi ast)))

(defn plan-transform
  [ast]
  (transform ast plan-transform-impl))


(defmulti plan-emit*
  "Output a transformed plan function"
  :op)


(defn plan-emit
  "Wrap plan-emit* with code to set form metadata."
  [{:keys [env] :as ast-node}]
  (let [form (plan-emit* ast-node)]
    (if (or (seq? form) (symbol? form))
      (with-meta
        form
        (merge
         (when-let [line (:line env)] {:line (int line)})
         (when-let [source (:source env)] {:source source})))
      form)))

(defmethod plan-emit* :nil [{:keys [val]}] val)
(defmethod plan-emit* :number [{:keys [val]}] val)

(defn- maybe-quote [s]
  (if (symbol? s) (list 'quote s) s))

(defmethod plan-emit* :constant
  [{:keys [val]}]
  (cond
   (instance? clojure.lang.Namespace val) `(find-ns '~(ns-name val))
   (symbol? val) (list 'quote val)
   (vector? val) (mapv maybe-quote val)
   (set? val) (set (mapv maybe-quote val))
   (map? val) (zipmap (map maybe-quote (keys val)) (map maybe-quote (vals val)))
   (seq? val) (into (empty val) (map maybe-quote val))
   :else val))

(defmethod plan-emit* :string [{:keys [val]}] val)
(defmethod plan-emit* :boolean [{:keys [val]}] val)
(defmethod plan-emit* :keyword [{:keys [val]}] val)

(defmethod plan-emit* :static-method
  [{:keys [class method-name args]}]
  `(~(symbol (.getName class) (str method-name))
       ~@(map plan-emit args)))

(defmethod plan-emit* :static-field
  [{:keys [class field-name]}]
  (symbol (.getName class) (str field-name)))

(defmethod plan-emit* :invoke
  [{:keys [fexpr args]}]
  (if (some :pallet/node-value args)
    (if (:pallet/plan-fn fexpr)
      `(~(plan-emit fexpr)
        ~@(map
           #(if (:pallet/node-value %)
              `(pallet.argument/delayed ~(plan-emit %))
              (plan-emit %))
           args))
      `(pallet.actions/as-action
        (~(plan-emit fexpr)
         ~@(map plan-emit args))))
    `(~(plan-emit fexpr)
      ~@(map plan-emit args))))

(defmethod plan-emit* :var
  [{:keys [var]}]
  (symbol (str (ns-name (.ns var))) (str (.sym var))))

(defmethod plan-emit* :the-var
  [{:keys [var]}]
  (list `var (symbol (str (ns-name (.ns var))) (str (.sym var)))))

(defmethod plan-emit* :instance-method
  [{:keys [target method-name args]}]
  `(~(symbol (str "." method-name))
       ~(plan-emit target)
       ~@(map plan-emit args)))

(defmethod plan-emit* :new
  [{:keys [class args]}]
  `(new ~(symbol (.getName class))
        ~@(map plan-emit args)))

(defmethod plan-emit* :empty-expr [{:keys [coll]}] coll)
(defmethod plan-emit* :vector [{:keys [args]}] (vec (map plan-emit args)))
(defmethod plan-emit* :map [{:keys [keyvals]}] (apply hash-map (map plan-emit keyvals)))
(defmethod plan-emit* :set [{:keys [keys]}] (set (map plan-emit keys)))

(defmethod plan-emit* :fn-expr
  [{:keys [name methods variadic-method]}]
  `(fn* ~@(when name [name])
        ~@(map
           plan-emit
           (distinct
            (concat methods (when variadic-method [variadic-method]))))))

(defmethod plan-emit* :fn-method
  [{:keys [body required-params rest-param]}]
  `(~(vec (concat (map plan-emit required-params)
                  (when rest-param
                    ['& (plan-emit rest-param)])))
       ~(plan-emit body)))

(defmethod plan-emit* :do
  [{:keys [exprs] :as ast-node}]
  (cond
    (empty? exprs) nil
    ; (= 1 (count exprs)) (plan-emit (first exprs))
    :else (if (::implicit-do ast-node)
            (map plan-emit exprs)
            `(do ~@(map plan-emit exprs)))))

(defmethod plan-emit* :let
  [{:keys [is-loop binding-inits body]}]
  `(~(if is-loop
       'loop*
       'let*)
    ~(vec (apply concat (map plan-emit binding-inits)))
    ~@(plan-emit (assoc body ::implicit-do true))))

(defmethod plan-emit* :recur
  [{:keys [args]}]
  `(recur ~@(map plan-emit args)))

;to be spliced
(defmethod plan-emit* :binding-init
  [{:keys [local-binding init]}]
  (map plan-emit [local-binding init]))

(defmethod plan-emit* :local-binding
  [{:keys [sym] :as ast}]
  (if (get-in ast [:pallet/local-env sym :pallet/node-value])
    `(deref ~sym)
    sym))

(defmethod plan-emit* :local-binding-expr
  [{:keys [local-binding]}] (plan-emit local-binding))

(defn has-branch? [{:keys [op] :as branch-ast}]
  (not= op :nil))

(defmethod plan-emit* :if
  [{:keys [test then else]}]
  (if (:pallet/node-value test)
    (cond
     (and (has-branch? then) (not (has-branch? else)))
     `(pallet.action/plan-when
       ~(plan-emit test)
       ~@(plan-emit (assoc then ::implicit-do true)))

     (and (not (has-branch? then)) (has-branch? else))
     `(pallet.action/plan-when-not
       ~(plan-emit test)
       ~@(plan-emit (assoc else ::implicit-do true)))

     :else (assert "general if not supported"))

    (cond
     (and (has-branch? then) (not (has-branch? else)))
     `(when ~(plan-emit test)
        ~@(plan-emit (assoc then ::implicit-do true)))

     (and (not (has-branch? then)) (has-branch? else))
     `(when-not ~(plan-emit test)
        ~@(plan-emit (assoc else ::implicit-do true)))

     :else `(if ~@(map plan-emit [test then else])))))

(defmethod plan-emit* :case*
  [{:keys [the-expr tests thens default]}]
  (if (:pallet/node-value the-expr)
    (assert "Case not supported in plan functions")
    `(case ~(plan-emit the-expr)
       ~@(mapcat vector (map plan-emit tests) (map plan-emit thens))
       ~@(when default [(plan-emit default)]))))

(defmethod plan-emit* :instance-of
  [{:keys [class the-expr]}]
  `(clojure.core/instance? ~(symbol (.getName class))
                           ~(plan-emit the-expr)))

(defmethod plan-emit* :def
  [{:keys [var init init-provided]}]
  `(def ~(.sym var) ~(when init-provided
                       (plan-emit init))))

;FIXME: methods don't print protocol/interface name
(defmethod plan-emit* :deftype*
  [{:keys [name methods]}]
  (list* 'deftype* name 'FIXME
         (map plan-emit methods)))

(defmethod plan-emit* :new-instance-method
  [{:keys [name required-params body]}]
  (list name (vec (map plan-emit required-params))
        (plan-emit body)))

(defmethod plan-emit* :import*
  [{:keys [class-str]}]
  (list 'import* class-str))

(defmethod plan-emit* :keyword-invoke
  [{:keys [kw target]}]
  (list (plan-emit kw) (plan-emit target)))

(defmethod plan-emit* :throw
  [{:keys [exception]}]
  (list 'throw (plan-emit exception)))

(defmethod plan-emit* :try
  [{:keys [try-expr catch-exprs finally-expr ]}]
  `(try ~@(plan-emit (assoc try-expr ::implicit-do true))
        ~@(concat
           (map plan-emit catch-exprs)
           (when finally-expr
             [`(finally
                ~@(plan-emit (assoc finally-expr ::implicit-do true)))]))))

(defmethod plan-emit* :catch
  [{:keys [class local-binding handler]}]
  `(catch ~class ~(plan-emit local-binding)
       ~@(plan-emit (assoc handler ::implicit-do true))))


(defn unwrap-ast
  "Remove the (fn []) wrapper added by analyze."
  [ast]
  (-> ast
      (get-in [:fexpr :methods])
      first
      :body
      :exprs
      first))


(defn plan-rewrite [nsym form]
  (-> (plan-fn-ast
       nsym
       form)
                                        ; unwrap-ast
      plan-transform
      first
      plan-emit))


;; '(let [x 1 y (xx 1 2)]
;;          (xx x y)
;;          (when x (println x))
;;          (when-not y (println y)))

;; (let*
;;  [x 1 y (pallet.core.transform/xx 1 2)]
;;  (do
;;   (pallet.core.transform/xx x (pallet.argument/delayed @y))
;;   (clojure.core/when x (clojure.core/println x))
;;   (pallet.action/plan-when-not
;;    @y
;;    (pallet.actions/as-action (clojure.core/println @y)))))




;; {:pallet/local-env {y {:pallet/node-value true}, x {}},
;;  :op :let,
;;  :env
;;  {:source "SOURCE_FORM_5364",
;;   :line 8,
;;   :locals {},
;;   :ns {:name pallet.core.transform}},
;;  :binding-inits
;;  [{:op :binding-init,
;;    :env {:locals {}, :ns {:name pallet.core.transform}},
;;    :local-binding
;;    {:op :local-binding,
;;     :env {:locals {}, :ns {:name pallet.core.transform}},
;;     :sym x,
;;     :tag nil,
;;     :init
;;     {:op :number,
;;      :env {:locals {}, :ns {:name pallet.core.transform}},
;;      :val 1}},
;;    :init
;;    {:op :number,
;;     :env {:locals {}, :ns {:name pallet.core.transform}},
;;     :val 1}}
;;   {:pallet/node-value true,
;;    :op :binding-init,
;;    :env
;;    {:source "SOURCE_FORM_5364",
;;     :line 8,
;;     :locals {},
;;     :ns {:name pallet.core.transform}},
;;    :local-binding
;;    {:op :local-binding,
;;     :env
;;     {:source "SOURCE_FORM_5364",
;;      :line 8,
;;      :locals {},
;;      :ns {:name pallet.core.transform}},
;;     :sym y,
;;     :tag nil,
;;     :init
;;     {:args
;;      ({:op :number,
;;        :env {:locals {}, :ns {:name pallet.core.transform}},
;;        :val 1}
;;       {:op :number,
;;        :env {:locals {}, :ns {:name pallet.core.transform}},
;;        :val 2}),
;;      :op :invoke,
;;      :protocol-on nil,
;;      :is-protocol false,
;;      :fexpr
;;      {:op :var,
;;       :env {:locals {}, :ns {:name pallet.core.transform}},
;;       :var
;;       #<Var@50316586:
;;         #<transform$xx pallet.core.transform$xx@6b3b5795>>,
;;       :tag nil},
;;      :is-direct false,
;;      :env
;;      {:source "SOURCE_FORM_5364",
;;       :line 8,
;;       :locals {},
;;       :ns {:name pallet.core.transform}},
;;      :site-index -1,
;;      :tag nil}},
;;    :init
;;    {:args
;;     [{:op :number,
;;       :env {:locals {}, :ns {:name pallet.core.transform}},
;;       :val 1}
;;      {:op :number,
;;       :env {:locals {}, :ns {:name pallet.core.transform}},
;;       :val 2}],
;;     :op :invoke,
;;     :protocol-on nil,
;;     :is-protocol false,
;;     :fexpr
;;     {:pallet/plan-fn true,
;;      :op :var,
;;      :env {:locals {}, :ns {:name pallet.core.transform}},
;;      :var
;;      #<Var@50316586:
;;        #<transform$xx pallet.core.transform$xx@6b3b5795>>,
;;      :tag nil},
;;     :is-direct false,
;;     :env
;;     {:source "SOURCE_FORM_5364",
;;      :line 8,
;;      :locals {},
;;      :ns {:name pallet.core.transform}},
;;     :site-index -1,
;;     :tag nil}}],
;;  :body
;;  {:pallet/node-value true,
;;   :pallet/local-env {y {:pallet/node-value true}, x {}},
;;   :op :do,
;;   :env
;;   {:source "SOURCE_FORM_5364",
;;    :line 8,
;;    :locals {},
;;    :ns {:name pallet.core.transform}},
;;   :exprs
;;   [{:args
;;     [{:pallet/local-env {y {:pallet/node-value true}, x {}},
;;       :op :local-binding-expr,
;;       :env {:locals {}, :ns {:name pallet.core.transform}},
;;       :local-binding
;;       {:pallet/local-env {y {:pallet/node-value true}, x {}},
;;        :op :local-binding,
;;        :env {:locals {}, :ns {:name pallet.core.transform}},
;;        :sym x,
;;        :tag nil,
;;        :init
;;        {:op :number,
;;         :env {:locals {}, :ns {:name pallet.core.transform}},
;;         :val 1}},
;;       :tag nil}
;;      {:pallet/local-env {y {:pallet/node-value true}, x {}},
;;       :op :local-binding-expr,
;;       :env
;;       {:source "SOURCE_FORM_5364",
;;        :line 8,
;;        :locals {},
;;        :ns {:name pallet.core.transform}},
;;       :local-binding
;;       {:pallet/local-env {y {:pallet/node-value true}, x {}},
;;        :op :local-binding,
;;        :env
;;        {:source "SOURCE_FORM_5364",
;;         :line 8,
;;         :locals {},
;;         :ns {:name pallet.core.transform}},
;;        :sym y,
;;        :tag nil,
;;        :init
;;        {:args
;;         ({:op :number,
;;           :env {:locals {}, :ns {:name pallet.core.transform}},
;;           :val 1}
;;          {:op :number,
;;           :env {:locals {}, :ns {:name pallet.core.transform}},
;;           :val 2}),
;;         :op :invoke,
;;         :protocol-on nil,
;;         :is-protocol false,
;;         :fexpr
;;         {:op :var,
;;          :env {:locals {}, :ns {:name pallet.core.transform}},
;;          :var
;;          #<Var@50316586:
;;            #<transform$xx pallet.core.transform$xx@6b3b5795>>,
;;          :tag nil},
;;         :is-direct false,
;;         :env
;;         {:source "SOURCE_FORM_5364",
;;          :line 8,
;;          :locals {},
;;          :ns {:name pallet.core.transform}},
;;         :site-index -1,
;;         :tag nil}},
;;       :tag nil}],
;;     :pallet/local-env {y {:pallet/node-value true}, x {}},
;;     :op :invoke,
;;     :protocol-on nil,
;;     :is-protocol false,
;;     :fexpr
;;     {:pallet/plan-fn true,
;;      :pallet/local-env {y {:pallet/node-value true}, x {}},
;;      :op :var,
;;      :env {:locals {}, :ns {:name pallet.core.transform}},
;;      :var
;;      #<Var@50316586:
;;        #<transform$xx pallet.core.transform$xx@6b3b5795>>,
;;      :tag nil},
;;     :is-direct false,
;;     :env
;;     {:source "SOURCE_FORM_5364",
;;      :line 8,
;;      :locals {},
;;      :ns {:name pallet.core.transform}},
;;     :site-index -1,
;;     :tag nil}]},
;;  :is-loop false}


;; (clojure.pprint/pprint
;;  (plan-emit
;;   (plan-transform
;;    (plan-fn-ast
;;     (ns-name *ns*)
;;     `(let [~'x 1 ~'y (xx 1 2)] (xx ~'x ~'y))))))

;; (clojure.pprint/pprint
;;  (plan-transform
;;   (plan-fn-ast
;;    (ns-name *ns*)
;;    `(pallet.stevedore/script (var a b)))))

;; (clojure.pprint/pprint
;;  (plan-fn-ast
;;   (ns-name *ns*)
;;   `(for [a# (range 0)] a#)))

;; (clojure.pprint/pprint
;;  (map->form
;;   (plan-fn-ast
;;    (ns-name *ns*)
;;    `(for [a# (range 0)] a#))))

;; (clojure.pprint/with-pprint-dispatch
;;   clojure.pprint/code-dispatch
;;   (clojure.pprint/pprint
;;    (macroexpand `(for [a# (range 0)] a#))))

;; (clojure.pprint/pprint
;; )


;; (defn traverse
;;   [ast]
;;   (transform ast traverse-impl))


;; ;;; plan-fn transformation
;; (defmulti plan-transform-pre
;;   "Updates the ast with plan rewrite information."
;;   (fn [ast global scoped] (:op ast)))
;; (defmulti plan-transform-post (fn [ast global scoped] (:op ast)))


;; (plan-transform
;;  {:op :invoke
;;   :args []
;;   :var "xxx"})
