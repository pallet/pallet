(ns pallet.compute.implementation
  "Implementation details"
  (:require
   [clojure.core.typed
    :refer [ann ann-form fn> for> inst
            Atom1 Nilable NilableNonEmptySeq Seq Set]]
   [chiba.plugin :refer [plugins]]
   [clojure.tools.logging :as logging]
   [pallet.core.types                   ; before any protocols
    :refer [ProviderIdentifier Symbol]]
   [pallet.compute.protocols :refer [ComputeService]]))

(ann service [ProviderIdentifier
              (HMap :optional {:credential String :identity String})
              -> ComputeService])
(defmulti service
  "Instantiate a compute service. Providers should implement a method for this.
   See pallet.compute/instantiate-provider."
  (fn [provider & _] provider))


(ann compute-prefix String)
(def compute-prefix "pallet.compute")

(ann exclude-compute-ns (Set Symbol))
(def exclude-compute-ns
  #{'pallet.compute
    'pallet.compute.jvm
    'pallet.compute.implementation})

(ann exclude-regex java.util.regex.Pattern)
(def exclude-regex #".*test.*")

(ann provider-list (Atom1 (NilableNonEmptySeq Symbol)))
(def provider-list (atom nil))

(ann ^:no-check providers [-> (NilableNonEmptySeq Symbol)])
;; TODO :no-check for regex as function
(defn- providers
  "Find the available providers."
  []
  (->> (plugins compute-prefix exclude-regex)
       (remove exclude-compute-ns)
       seq))

(ann load-providers [-> (NilableNonEmptySeq Symbol)])
(defn load-providers
  "Require all providers, ensuring no errors if individual providers can not be
   loaded"
  []
  (when-not @provider-list
    (reset! provider-list (providers))
    ;; TODO - figure out why ann-form is needed
    (let [loaded (->>
                  (for> :- (Nilable Symbol)
                        [provider :- Symbol @provider-list]
                        (try
                          (require provider)
                          provider
                          (catch Throwable e
                            (logging/debugf
                             "%s provider failed to load: %s"
                             provider
                             (.getMessage e)))))
                  (filter symbol?)
                  doall
                  seq)]
      (reset! provider-list loaded)))
  @provider-list)

;; TODO replace with var> when it has a two arg version
(ann ^:no-check resolve-supported-providers
     [Symbol -> (Nilable (clojure.lang.IDeref [-> (Seq ProviderIdentifier)]))])
(defn- resolve-supported-providers
  "Function to provide a type to the result of ns-resolve."
  [ns-sym]
  (ns-resolve ns-sym 'supported-providers))

(ann supported-providers [-> (NilableNonEmptySeq ProviderIdentifier)])
(defn supported-providers
  "Create a list of supported providers"
  []
  (->>
   (for> :- (NilableNonEmptySeq ProviderIdentifier)
         [provider :- Symbol (load-providers)]
         (when-let [providers (resolve-supported-providers provider)]
           (seq (@(ann-form
                   providers
                   (clojure.lang.IDeref [-> (Seq ProviderIdentifier)]))))))
   (apply concat)
   seq))
