(ns pallet.maven
  "Maven interaction"
  (:require pallet.compat)
  (:import
   [org.apache.maven.settings Settings MavenSettingsBuilder]
   [org.codehaus.plexus.embed Embedder]))

(pallet.compat/require-contrib)

(def container (.getContainer (doto (Embedder.) (.start))))

(defn make-settings []
  (.buildSettings (.lookup container MavenSettingsBuilder/ROLE)))

;; (defn read-maven-settings
;;   []
;;   (let [builder (DefaultMavenSettingsBuilder.)]
;;     (set! (. DefaultMavenSettingsBuilder userSettingsPath)
;;           (.getPath (file (System/getProperty "user.home") ".m2" "settings.xml")))
;;     (assert builder)
;;     (.initialize builder)
;;     (.buildSettings builder)))
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
