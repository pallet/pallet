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
if they are (is this advanced use?)."
  (:require
   [pallet.core.transform.ast
    :refer [analyse-form deftraversal emit-node form-with-metadata traverse
            with-emit-fn]]))

(defn plan-fn-ast
  "Generate the AST for a form."
  [nsym form]
  (analyse-form nsym form))

;;; ## Plan Function AST Transformation

;;; The transformation is based on using :pallet/plan-fn metadata on pallet's
;;; actions and plan functions to identify the values that are not available
;;; until execution time.

;;; In order to make this information available, we add a map of local bindings
;;; using the :pallet/local-env, that maps from local symbol to a map of
;;; properties, that will contain a :pallet/node-value member for locals bound
;;; to expressions containing execution time values.

;;; Define a base traversal for our transformation
(deftraversal
  plan-traversal
  {:let [:binding-inits :body]}
  #{:pallet/local-env})

;;; plan-fn transformation
(defmulti plan-transform-impl
  "Updates the ast with plan rewrite information."
  :op)

;; Default node traversal to `plan-traversal`
(defmethod plan-transform-impl :default
  [ast]
  (plan-traversal ast))

;; Local bindings should propagate up to the containing binding form, so
;; we can build a symbol table
(defmethod plan-transform-impl :binding-init
  [ast-node]
  (let [[{:keys [local-binding init] :as node} parent]
        (plan-traversal ast-node)]
    [node
     (assoc parent :pallet/local-env
            {(:sym local-binding) (select-keys node [:pallet/node-value])})]))

;; Using a local binding should propagate `:pallet/node-value` back to the
;; parent node.
(defmethod plan-transform-impl :local-binding
  [{:keys [sym] :as ast-node}]
  [ast-node
   (when (get-in ast-node [:pallet/local-env sym :pallet/node-value])
     {:pallet/node-value true})])

;;; Invoking a function with :pallet/plan-fn metadata should annotate the parent
;;; expression as being a :pallet/node-value.
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


(defn plan-transform
  "Apply the plan-fn AST transform on the specified `ast`."
  [ast]
  (traverse ast plan-transform-impl))

;;; ## Plan Function Form Generation

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

(defmethod plan-emit* :default
  [ast-node]
  (emit-node ast-node))

(defmethod plan-emit* :invoke
  [{:keys [fexpr args]}]
  (if (some :pallet/node-value args)
    (if (:pallet/plan-fn fexpr)
      `(~(plan-emit* fexpr)
        ~@(map
           #(if (:pallet/node-value %)
              `(pallet.argument/delayed ~(plan-emit* %))
              (plan-emit* %))
           args))
      `(pallet.actions/as-action
        (~(plan-emit* fexpr)
         ~@(map plan-emit* args))))
    `(~(plan-emit* fexpr)
      ~@(map plan-emit* args))))

(defmethod plan-emit* :local-binding
  [{:keys [sym] :as ast}]
  (if (get-in ast [:pallet/local-env sym :pallet/node-value])
    `(deref ~sym)
    sym))

(defn has-branch? [{:keys [op] :as branch-ast}]
  (not= op :nil))

(defmethod plan-emit* :if
  [{:keys [test then else]}]
  (if (:pallet/node-value test)
    (cond
     (and (has-branch? then) (not (has-branch? else)))
     `(pallet.action/plan-when
       ~(plan-emit* test)
       ~@(plan-emit* (assoc then :implicit-do true)))

     (and (not (has-branch? then)) (has-branch? else))
     `(pallet.action/plan-when-not
       ~(plan-emit* test)
       ~@(plan-emit* (assoc else :implicit-do true)))

     :else (assert "general if not supported"))

    (cond
     (and (has-branch? then) (not (has-branch? else)))
     `(when ~(plan-emit* test)
        ~@(plan-emit* (assoc then :implicit-do true)))

     (and (not (has-branch? then)) (has-branch? else))
     `(when-not ~(plan-emit* test)
        ~@(plan-emit* (assoc else :implicit-do true)))

     :else `(if ~@(map plan-emit* [test then else])))))

(defmethod plan-emit* :case*
  [{:keys [the-expr tests thens default]}]
  (if (:pallet/node-value the-expr)
    (assert "Case not supported in plan functions")
    `(case ~(plan-emit* the-expr)
       ~@(mapcat vector (map plan-emit* tests) (map plan-emit* thens))
       ~@(when default [(plan-emit* default)]))))

(defn plan-emit-dispatch
  [ast]
  (-> ast plan-emit* (form-with-metadata ast)))

(defn plan-emit
  [ast]
  (with-emit-fn plan-emit-dispatch
    (plan-emit-dispatch ast)))

(defn plan-rewrite
  "Rewrite a defplan form."
  [nsym form]
  (-> (plan-fn-ast nsym form)
      plan-transform
      first
      plan-emit))

(defn unwrap-ast
  "Remove the (fn []) wrapper added by analyze."
  [ast]
  (-> ast
      (get-in [:fexpr :methods])
      first
      :body
      :exprs
      first))
