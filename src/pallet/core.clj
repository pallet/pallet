(ns pallet.core
  "Namespace for compatibility with pallet 0.7.x and earlier")

(defn var-name
  "Get the namespace-qualified name of a var."
  [v]
  (apply symbol (map str ((juxt (comp ns-name :ns)
                                :name)
                          (meta v)))))

(defn alias-var
  "Create a var with the supplied name in the current namespace, having the same
  metadata and root-binding as the supplied var."
  [name ^clojure.lang.Var var]
  (apply intern *ns* (with-meta name (merge {:dont-test (str "Alias of " (var-name var))}
                                            (meta var)
                                            (meta name)))
         (when (.hasRoot var) [@var])))

(defn alias-ns
  "Create vars in the current namespace to alias each of the public vars in
  the supplied namespace."
  [ns-name]
  (require ns-name)
  (doseq [[name var] (ns-publics (the-ns ns-name))]
    (alias-var name var)))

(alias-ns 'pallet.api)
