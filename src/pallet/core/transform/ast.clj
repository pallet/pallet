(ns pallet.core.transform.ast
  "AST Transformation

This is a wrapper for the analyze lib, which adds helpers, an open coded AST
traversal, and an open coded AST to form function."
  (:require
   [analyze.core :as analyze :refer [analyze-form-in-ns]])
  (:import [clojure.lang RT Compiler]))

;;; ## Analysis
(defmacro ^:private form-thread-bindings
  "Provide a map of thread bindings to be used when analysing a single clojure
  form."
  [source-path source-nsym line]
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

(defn analyse-form
  "Return the AST map for a single form.  Note that the form is wrapped in a
   anonymous function definition of no arguments, and a call to that function."
  [nsym form]
  (push-thread-bindings
   (form-thread-bindings
    (or *file* "UNKNOWN") nsym (or (-> form meta :line) 1)))
  (try
    (analyze-form-in-ns nsym form)
    (finally (pop-thread-bindings))))

;;; ## AST Transformation

;;; We define a traversal by generating a multi-method dispatched on the AST
;;; node :op member, based on `ast-node-structure`, which describes the (simple)
;;; child nodes, child-sequence nodes, and fields (not used).

;;; Each method in the generated multi-method handles the traversal of a single
;;; :op, and calls the transformation for each child node via a dynamic var,
;;; `*traversal-fn*`.  The caller can then provide custom processing of the AST
;;; by binding a function to the var.  One strategy is to bind a multimethod,
;;; which by default dispatches to the traversal multimethod, and just provides
;;; implementations for the AST node `:op`s that it needs to customise.

;;; Each traversal function returns an updated ast, and a map for updating the
;;; parent node in the ast.


;;; ### AST Node structure
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

;;; ### Helpers
(defn deep-merge
  "Recursively merge maps."
  [& ms]
  (letfn [(f [a b]
            (if (and (map? a) (map? b))
              (deep-merge a b)
              b))]
    (apply merge-with f ms)))

;;; ### AST Traversal Function

(def ^{:dynamic true
       :interal true
       :doc "Provides a customisation point for the traversal"} *traversal-fn*)

(defmacro with-traversal-fn
  "Define the dispatch function for traversal"
  [f & body]
  `(binding [*traversal-fn* ~f]
     ~@body))

(defn traverse
  "Traverse the given ast using the specified traversal function."
  [ast multi]
  (with-traversal-fn multi
    (multi ast)))

;;; ### Child Update Functions

;;; Provides a node update where the transformation of a node can pass a map
;;; back up to be merged in the parent node, and a set of parent node members
;;; are passed down to each child node.

(defn update-node
  "Transforms a child in an AST node. Takes an AST node `ast-node`, a keyword
  `kw` identifying a child in that node, and a transformation function `f` and a
  sequence of node member keywords `pd-keys`.  The transformation function is
  applied to the child node merged with the `ast-node` keys specified by
  `pd-keys`.  It is expected to return a vector containing the modified node,
  and a map which is merged into the parent node."
  [ast-node kw f pd-keys]
  (if-let [v (get ast-node kw)] ; e.g. :variadic-method is optional in :fn-expr
    (let [[child parent] (f (merge v (select-keys ast-node pd-keys)))]
      (-> ast-node
          (assoc kw child)
          (deep-merge parent)))
    ast-node))

(defn update-node-seq
  "Transforms a child sequence in an AST node. Takes an AST node `ast-node`, a
  keyword `kw` identifying a child sequence in that node, and a transformation
  function `f` and a sequence of node member keywords `pd-keys`.  The
  transformation function is applied to the child sequence's nodes merged with
  the `ast-node` keys specified by `pd-keys`.  It is expected to return a vector
  containing the modified node, and a map which is merged into the parent node."
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

;;; ### Traversal

;;; The `implement-traversal` macro will generate a multi-method that implements
;;; AST traversal.

(defn ^:internal implement-traversal-node
  "Create a traversal method for a node that has a deterministic order of child
   visits, and pushes the selected keys down into the children."
  [name kw key-seq pd-keys]
  (let [struct (get ast-node-structure kw)
        children (set (:children struct))
        child-seqs (set (:child-seqs struct))]
    `(defmethod ~name ~kw
       [ast-node#]
       [(-> ast-node#
            ~@(map
               #(cond
                 (children %)
                 `(update-node ~% *traversal-fn* ~pd-keys)

                 (child-seqs %)
                 `(update-node-seq ~% *traversal-fn* ~pd-keys)

                 :else (assert nil (str "Trying invalid key " % " for op " kw)))
               key-seq))
        nil])))

(defmacro deftraversal
  "Defines a traversal multi-method.  The `key-sequence-map` is a map from AST
  node :op to a sequence of keywords, specifying a traversal order for child or
  child-seq nodes. `pd-keys` specifies a sequence of parent AST node keys that
  should be passed down to each child node during the traversal. The generated
  traversal passes no map to the parent nodes."
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
          #(implement-traversal-node
             name %1 (key-sequence %1) pd-keys)
          (keys ast-node-structure)))))


;;; ## AST Output

;;; Generate forms based on the AST.  The traversal for output is through the
;;; `emit-fn` var.  In order to keep emitting forms lazy, we can not use a
;;; dynamic var as any binding can go out of scope before the sequence is
;;; realised.  Instead we use a thread-local

(def ^{:internal true
       :doc "Provides a customisation point for the output"}
  emit-fn-var (ThreadLocal.))

(defn emit-fn! [f]
  (.set emit-fn-var f))

(defn emit-fn [ast]
  ((.get emit-fn-var) ast))

(defmacro with-emit-fn
  "Define the dispatch function for emit"
  [f & body]
  `(do
     (emit-fn! ~f)
     ~@body))

;;; Base output multi-method
(defmulti emit-node
  "Output a transformed plan function"
  :op)

(defmethod emit-node :nil [{:keys [val]}] val)
(defmethod emit-node :number [{:keys [val]}] val)

(defn- maybe-quote [s]
  (if (symbol? s) (list 'quote s) s))

(defmethod emit-node :constant
  [{:keys [val]}]
  (cond
   (instance? clojure.lang.Namespace val) `(find-ns '~(ns-name val))
   (symbol? val) (list 'quote val)
   (vector? val) (mapv maybe-quote val)
   (set? val) (set (mapv maybe-quote val))
   (map? val) (zipmap (map maybe-quote (keys val)) (map maybe-quote (vals val)))
   (seq? val) (into (empty val) (map maybe-quote val))
   :else val))

(defmethod emit-node :string [{:keys [val]}] val)
(defmethod emit-node :boolean [{:keys [val]}] val)
(defmethod emit-node :keyword [{:keys [val]}] val)

(defmethod emit-node :static-method
  [{:keys [class method-name args]}]
  `(~(symbol (.getName class) (str method-name))
       ~@(map emit-fn args)))

(defmethod emit-node :static-field
  [{:keys [class field-name]}]
  (symbol (.getName class) (str field-name)))

(defmethod emit-node :invoke
  [{:keys [fexpr args]}]
  `(~(emit-fn fexpr) ~@(map emit-fn args)))

(defmethod emit-node :var
  [{:keys [var]}]
  (symbol (str (ns-name (.ns var))) (str (.sym var))))

(defmethod emit-node :the-var
  [{:keys [var]}]
  (list `var (symbol (str (ns-name (.ns var))) (str (.sym var)))))

(defmethod emit-node :instance-method
  [{:keys [target method-name args]}]
  `(~(symbol (str "." method-name))
       ~(emit-fn target)
       ~@(map emit-fn args)))

(defmethod emit-node :new
  [{:keys [class args]}]
  `(new ~(symbol (.getName class))
        ~@(map emit-fn args)))

(defmethod emit-node :empty-expr [{:keys [coll]}] coll)
(defmethod emit-node :vector [{:keys [args]}] (vec (map emit-fn args)))
(defmethod emit-node :map [{:keys [keyvals]}] (apply hash-map (map emit-fn keyvals)))
(defmethod emit-node :set [{:keys [keys]}] (set (map emit-fn keys)))

(defmethod emit-node :fn-expr
  [{:keys [name methods variadic-method]}]
  `(fn* ~@(when name [name])
        ~@(map
           emit-fn
           (distinct
            (concat methods (when variadic-method [variadic-method]))))))

(defmethod emit-node :fn-method
  [{:keys [body required-params rest-param]}]
  `(~(vec (concat (map emit-fn required-params)
                  (when rest-param
                    ['& (emit-fn rest-param)])))
    ~@(emit-fn (assoc body :implicit-do true))))

(defmethod emit-node :do
  [{:keys [exprs] :as ast-node}]
  (cond
    (empty? exprs) nil
    ; (= 1 (count exprs)) (emit-fn (first exprs))
    :else (if (:implicit-do ast-node)
            (map emit-fn exprs)
            `(do ~@(map emit-fn exprs)))))

(defmethod emit-node :let
  [{:keys [is-loop binding-inits body]}]
  `(~(if is-loop
       'loop*
       'let*)
    ~(vec (apply concat (map emit-fn binding-inits)))
    ~@(emit-fn (assoc body :implicit-do true))))

(defmethod emit-node :recur
  [{:keys [args]}]
  `(recur ~@(map emit-fn args)))

;to be spliced
(defmethod emit-node :binding-init
  [{:keys [local-binding init]}]
  (map emit-fn [local-binding init]))

(defmethod emit-node :local-binding
  [{:keys [sym] :as ast}]
  sym)

(defmethod emit-node :local-binding-expr
  [{:keys [local-binding]}] (emit-fn local-binding))

(defn has-branch? [{:keys [op] :as branch-ast}]
  (not= op :nil))

(defmethod emit-node :if
  [{:keys [test then else]}]
  (cond
   (and (has-branch? then) (not (has-branch? else)))
   `(when ~(emit-fn test)
      ~@(emit-fn (assoc then :implicit-do true)))

   (and (not (has-branch? then)) (has-branch? else))
   `(when-not ~(emit-fn test)
      ~@(emit-fn (assoc else :implicit-do true)))

   :else `(if ~@(map emit-fn [test then else]))))

(defmethod emit-node :case*
  [{:keys [the-expr tests thens default]}]
  `(case ~(emit-fn the-expr)
     ~@(mapcat vector (map emit-fn tests) (map emit-fn thens))
     ~@(when default [(emit-fn default)])))

(defmethod emit-node :instance-of
  [{:keys [class the-expr]}]
  `(clojure.core/instance? ~(symbol (.getName class))
                           ~(emit-fn the-expr)))

(defmethod emit-node :def
  [{:keys [var init init-provided]}]
  `(def ~(.sym var) ~(when init-provided
                       (emit-fn init))))

;FIXME: methods don't print protocol/interface name
(defmethod emit-node :deftype*
  [{:keys [name methods]}]
  (list* 'deftype* name 'FIXME
         (map emit-fn methods)))

(defmethod emit-node :new-instance-method
  [{:keys [name required-params body]}]
  (list name (vec (map emit-fn required-params))
        (emit-fn body)))

(defmethod emit-node :import*
  [{:keys [class-str]}]
  (list 'import* class-str))

(defmethod emit-node :keyword-invoke
  [{:keys [kw target]}]
  (list (emit-fn kw) (emit-fn target)))

(defmethod emit-node :throw
  [{:keys [exception]}]
  (list 'throw (emit-fn exception)))

(defmethod emit-node :try
  [{:keys [try-expr catch-exprs finally-expr ]}]
  `(try ~@(emit-fn (assoc try-expr :implicit-do true))
        ~@(concat
           (map emit-fn catch-exprs)
           (when finally-expr
             [`(finally
                ~@(emit-fn (assoc finally-expr :implicit-do true)))]))))

(defmethod emit-node :catch
  [{:keys [class local-binding handler]}]
  `(catch ~class ~(emit-fn local-binding)
       ~@(emit-fn (assoc handler :implicit-do true))))


;;; A function to apply

(defn form-with-metadata
  "Apply the AST node's metadata to the given `form`."
  [form {:keys [env] :as ast-node}]
  (if (or (seq? form) (symbol? form))
    (with-meta
      form
      (merge
       (when-let [line (:line env)] {:line (int line)})
       (when-let [source (:source env)] {:source source})))
    form))
