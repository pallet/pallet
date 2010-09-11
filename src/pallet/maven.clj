(ns pallet.maven
  "Maven interaction"
  (:import
   [org.apache.maven.settings Settings MavenSettingsBuilder]
   [org.codehaus.plexus.embed Embedder]))

(def container (.getContainer (doto (Embedder.) (.start))))

(defn make-settings []
  (.buildSettings (.lookup container MavenSettingsBuilder/ROLE)))

(def provider-key "jclouds.compute.provider")
(def identity-key "jclouds.compute.identity")
(def credential-key "jclouds.compute.credential")

(defn has-pallet-properties
  [profile]
  (let [properties (.getProperties profile)]
    (and (.getProperty properties provider-key)
         profile)))

(defn get-property [profile key]
  (.getProperty profile key))

(defn credentials
  []
  (let [settings (make-settings)
        active-profiles (.getActiveProfiles settings)
        profiles (into {} (.getProfilesAsMap settings))
        profile (some has-pallet-properties (map profiles active-profiles))]
    (when profile
      (map (partial get-property (.getProperties profile))
           [provider-key identity-key credential-key]))))
