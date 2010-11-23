(ns pallet.compute.implementation
  "Implementation details"
  (:require
   [clojure.contrib.find-namespaces :as find-namespaces]))

(defmulti service
  "Instantiate a compute service. Providers should implement a method for this.
   See pallet.compute/compute-service."
  (fn [provider-name & _] (keyword provider-name)))


(def compute-regex #"^pallet\.compute\.[a-z-]+")
(def exclude-compute-ns
  #{'pallet.compute.jvm
    'pallet.compute.implementation})
(def exclude-regex #".*test.*")
(def provider-list (atom nil))

(defn- providers
  "Find the available providers."
  []
  (try
    (->> (find-namespaces/find-namespaces-on-classpath)
         (filter #(re-find compute-regex (name %)))
         (remove #(re-find exclude-regex (name %)))
         (remove exclude-compute-ns)
         (set))
    (catch java.io.FileNotFoundException _)))

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
                       (catch Throwable _)))))]
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
