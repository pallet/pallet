(ns pallet.maven
  "Maven interaction"
  (:import
   [org.apache.maven.settings Settings MavenSettingsBuilder]
   [org.codehaus.plexus.embed Embedder]))

(def key-map
  {:pallet.compute.provider :compute-provider
   :pallet.compute.identity :compute-identity
   :pallet.compute.credential :compute-credential
   :pallet.compute.extensions :compute-extensions
   :jclouds.compute.provider :compute-provider
   :jclouds.compute.identity :compute-identity
   :jclouds.compute.credential :compute-credential
   :jclouds.compute.extensions :compute-extensions
   :jclouds.blobstore.provider :blobstore-provider
   :jclouds.blobstore.identity :blobstore-identity
   :jclouds.blobstore.credential :blobstore-credential})

(def container (.getContainer (doto (Embedder.) (.start))))

(defn- make-settings []
  (.buildSettings (.lookup container MavenSettingsBuilder/ROLE)))

(defn properties
  "Read maven's settings.xml file, and extract properties from active profiles
   as a map."
  []
  (let [settings (make-settings)
        properties (apply
                    merge
                    (map #(into {} (.getProperties (val %)))
                         (select-keys
                          (into {} (.getProfilesAsMap settings))
                          (.getActiveProfiles settings))))]
    (zipmap (map keyword (keys properties)) (vals properties))))

(defn credentials
  "Read maven's settings.xml file, and extract credentials.  "
  []
  (into {}
        (filter identity
                (map
                 #(if-let [k (key-map (key %))] [k (val %)])
                 (properties)))))
