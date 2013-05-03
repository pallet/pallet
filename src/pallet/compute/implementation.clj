(ns pallet.compute.implementation
  "Implementation details"
  (:require
   [chiba.plugin :refer [plugins]]
   [clojure.tools.logging :as logging]))

(defmulti service
  "Instantiate a compute service. Providers should implement a method for this.
   See pallet.compute/instantiate-provider."
  (fn [provider-name & _] (keyword provider-name)))


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
       (remove exclude-compute-ns)))

(defn load-providers
  "Require all providers, ensuring no errors if individual providers can not be
   loaded"
  []
  (when-not @provider-list
    (reset! provider-list (providers))
    (let [loaded (filter
                  identity
                  (doall
                   (for [provider @provider-list]
                     (try
                       (require provider)
                       provider
                       (catch Throwable e
                         (logging/debugf
                          "%s provider failed to load: %s"
                          provider
                          (.getMessage e)))))))]
      (reset! provider-list loaded)))
  @provider-list)

(defn supported-providers
  "Create a list of supported providers"
  []
  (->>
   (doall
    (for [provider (load-providers)]
      (when-let [providers (ns-resolve provider 'supported-providers)]
        (@providers))))
   (filter identity)
   (apply concat)))
