(ns pallet.core.version-dispatch
  "Version dispatch"
  (:require
   [pallet.versions
    :refer [as-version-vector version-matches? version-spec-less]]))

(defn ^{:internal true} match-less
  [hierarchy [i _] [j _]]
  (let [osi (:os i)
        os-versioni (:os-version i)
        versioni (:version i)
        osj (:os j)
        os-versionj (:os-version j)
        versionj (:version j)]
    (cond
      (and (isa? hierarchy osi osj) (not (isa? hierarchy osj osi))) true
      (and (isa? hierarchy osj osi) (not (isa? hierarchy osi osj))) false
      (version-spec-less os-versioni os-versionj) true
      (version-spec-less os-versionj os-versioni) false
      :else (version-spec-less versioni versionj))))

;;; A map that is looked up based on os and os version. The key should be a map
;;; with :os and :os-version keys.
(defn ^{:internal true} lookup-os
  "Pass nil to default-value if non required"
  [base version hierarchy base-key version-key values default-value]
  (letfn [(matches? [[i _]]
            (and (isa? hierarchy base (base-key i))
                 (version-matches? version (version-key i))))]
    (if-let [[_ v] (first (sort
                           (comparator (partial match-less hierarchy))
                           (filter matches? values)))]
      v
      (if-let [[_ v] (:default values)]
        v
        default-value))))

(declare version-map)

(deftype VersionMap [^clojure.lang.IPersistentMap data
                     hierarchy
                     ^clojure.lang.Keyword base-key
                     ^clojure.lang.Keyword version-key]
  clojure.lang.ILookup
  (valAt [m key]
    (lookup-os
     (base-key key) (version-key key) hierarchy base-key version-key data nil))
  (valAt [m key default-value]
    (lookup-os
     (base-key key) (version-key key) hierarchy base-key version-key data
     default-value))
  clojure.lang.IFn
  (invoke [m key]
    (lookup-os
     (base-key key) (version-key key) hierarchy base-key version-key data nil))
  (invoke [m key default-value]
    (lookup-os
     (base-key key) (version-key key) hierarchy base-key version-key data
     default-value))
  clojure.lang.IPersistentMap
  (assoc [m key val]
    (version-map hierarchy base-key version-key (assoc data key val)))
  (assocEx [m key val]
    (version-map hierarchy base-key version-key (.assocEx data key val)))
  (without [m key]
    (version-map hierarchy base-key version-key (.without data key))))

(defn version-map
  "Construct a version map. The keys should be maps with `base-key` and
`version-key` keys. The `base-key` value should be take from the
`hierarchy`. The `version-key` value should be a version vector, or a version
range vector."
  ([hierarchy base-key version-key {:as os-value-pairs}]
     (VersionMap. os-value-pairs hierarchy base-key version-key))
  ([hierarchy base-key version-key]
     (VersionMap. {} hierarchy base-key version-key)))
