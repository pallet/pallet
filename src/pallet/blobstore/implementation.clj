(ns pallet.blobstore.implementation
  "Implementation details"
  (:require
   [chiba.plugin :refer [plugins]]))

(defmulti service
  "Instantiate a blobstore. Providers should implement a method for this.
   See pallet.blobstore/blobstore-service."
  (fn [provider-name & _] (keyword provider-name)))

(def blobstore-prefix "pallet.blobstore.")
(def exclude-blobstore-ns
  #{'pallet.blobstore.implementation})
(def exclude-regex #".*test.*")
(def provider-list (atom nil))

(defn- providers
  "Find the available providers."
  []
  (->> (plugins blobstore-prefix exclude-regex)
       (remove exclude-blobstore-ns)))

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
