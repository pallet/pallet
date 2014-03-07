(ns pallet.compute.implementation
  "Implementation details"
  (:require
   [chiba.plugin :refer [plugins]]
   [clojure.tools.logging :as logging]
   [pallet.compute.protocols :refer [ComputeService]]))

(defmulti service
  "Instantiate a compute service. Providers should implement a method for this.
   See pallet.compute/instantiate-provider."
  (fn [provider & _] provider))


(def compute-prefix "pallet.compute")

(def exclude-compute-ns
  #{'pallet.compute
    'pallet.compute.jvm
    'pallet.compute.implementation})

(def exclude-regex #".*test.*")

(def provider-list (atom nil))

(defn- providers
  "Find the available providers."
  []
  (->> (plugins compute-prefix exclude-regex)
       (remove exclude-compute-ns)
       seq))

(defn load-providers
  "Require all providers, ensuring no errors if individual providers can not be
   loaded"
  []
  (when-not @provider-list
    (reset! provider-list (providers))
    ;; TODO - figure out why ann-form is needed
    (let [loaded (->>
                  (for [provider @provider-list]
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

(defn- resolve-supported-providers
  "Function to provide a type to the result of ns-resolve."
  [ns-sym]
  (ns-resolve ns-sym 'supported-providers))

(defn supported-providers
  "Create a list of supported providers"
  []
  (->>
   (for [provider (load-providers)]
     (when-let [providers (resolve-supported-providers provider)]
       (seq (@providers))))
   (apply concat)
   seq))
