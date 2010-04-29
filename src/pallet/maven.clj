(ns pallet.maven
  "Maven interaction"
  (:import
   [org.apache.maven.settings Settings MavenSettingsBuilder]
   [org.codehaus.plexus.embed Embedder]))

(def container (.getContainer (doto (Embedder.) (.start))))

(defn make-settings []
  (.buildSettings (.lookup container MavenSettingsBuilder/ROLE)))

(def service-key "pallet.service")
(def user-key "pallet.user")
(def key-key "pallet.key")

(defn has-pallet-properties
  [profile]
  (let [properties (.getProperties profile)]
    (and (.getProperty properties service-key)
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
           [service-key user-key key-key]))))
