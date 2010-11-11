(ns pallet.maven
  "Maven interaction"
  (:import
   [org.apache.maven.settings Settings MavenSettingsBuilder]
   [org.codehaus.plexus.embed Embedder]))

(def key-map
  {:pallet.compute.provider :provider
   :pallet.compute.identity :identity
   :pallet.compute.credential :credential
   :pallet.compute.extensions :extensions
   :pallet.blobstore.provider :blobstore-provider
   :pallet.blobstore.identity :blobstore-identity
   :pallet.blobstore.credential :blobstore-credential
   :pallet.blobstore.extensions :blobstore-extensions
   :jclouds.compute.provider :provider
   :jclouds.compute.identity :identity
   :jclouds.compute.credential :credential
   :jclouds.compute.extensions :extensions
   :jclouds.blobstore.provider :blobstore-provider
   :jclouds.blobstore.identity :blobstore-identity
   :jclouds.blobstore.credential :blobstore-credential})

(def container (.getContainer (doto (Embedder.) (.start))))

(defn- make-settings []
  (.buildSettings (.lookup container MavenSettingsBuilder/ROLE)))

(defn properties
  "Read maven's settings.xml file, and extract properties from active profiles
   as a map."
  [profiles]
  (let [settings (make-settings)
        properties (apply
                    merge
                    (map #(into {} (.getProperties (val %)))
                         (select-keys
                          (into {} (.getProfilesAsMap settings))
                          (if (seq profiles)
                            profiles
                            (.getActiveProfiles settings)))))]
    (zipmap (map keyword (keys properties)) (vals properties))))

(defn credentials
  "Read maven's settings.xml file, and extract credentials.  "
  [profiles]
  (into {}
        (filter identity
                (map
                 #(if-let [k (key-map (key %))] [k (val %)])
                 (properties profiles)))))
