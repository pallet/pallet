(ns pallet.maven
  "Maven interaction"
  (:import
   [org.apache.maven.settings Settings MavenSettingsBuilder]
   [org.codehaus.plexus.embed Embedder]))

(defonce settings-keys
  {:compute
   ["jclouds.compute.provider"
    "jclouds.compute.identity"
    "jclouds.compute.credential"]
   :blobstore
   ["jclouds.blobstore.provider"
    "jclouds.blobstore.identity"
    "jclouds.blobstore.credential"]})

(def container (.getContainer (doto (Embedder.) (.start))))

(defn- make-settings []
  (.buildSettings (.lookup container MavenSettingsBuilder/ROLE)))

(defn- profile-with-credentials
  [facility-keys profile]
  (let [properties (.getProperties profile)]
    (and (.getProperty properties (first facility-keys))
         profile)))

(defn- get-property [profile key]
  (.getProperty profile key))

(defn credentials
  "Read maven's settings.xml file, and extract credentials.  By default get
   credentials for the compute service. The blobstore credentials may be
   retrieved by passing :blobstore"
  ([] (credentials :compute))
  ([facility]
     (let [settings (make-settings)
           facility-keys (facility settings-keys)
           active-profiles (.getActiveProfiles settings)
           profiles (into {} (.getProfilesAsMap settings))
           profile (some
                    #(profile-with-credentials facility-keys %)
                    (map profiles active-profiles))]
       (when profile
         (map (partial get-property (.getProperties profile)) facility-keys)))))

(defn properties
  "Read maven's settings.xml file, and extract properties as a map."
  []
  (let [settings (make-settings)]
    (apply
     merge
     (map #(into {} (.getProperties (val %)))
          (select-keys
           (into {} (.getProfilesAsMap settings))
           (.getActiveProfiles settings))))))
