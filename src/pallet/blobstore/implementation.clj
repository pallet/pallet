(ns pallet.blobstore.implementation
  "Implementation details"
  (:require
   [clojure.contrib.find-namespaces :as find-namespaces]))

(defmulti service
  "Instantiate a blobstore. Providers should implement a method for this.
   See pallet.blobstore/blobstore-service."
  (fn [provider-name & _] (keyword provider-name)))

(def blobstore-regex #"^pallet\.blobstore\.[a-z-]+")
(def exclude-blobstore-ns
  #{'pallet.blobstore.implementation})
(def exclude-regex #".*test.*")
(def provider-list (atom nil))

(defn- providers
  "Find the available providers."
  []
  (try
    (->> (find-namespaces/find-namespaces-on-classpath)
         (filter #(re-find blobstore-regex (name %)))
         (remove #(re-find exclude-regex (name %)))
         (remove exclude-blobstore-ns)
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
