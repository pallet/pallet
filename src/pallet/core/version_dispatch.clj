(ns pallet.core.version-dispatch
  "Version dispatch.

This is based on dispatching over a map.  The map has a `base` key, which
must be part of a `hierarchy`. The map also has a `version-key`, with a
value that must be a VersionSpec."
  (:require
   [pallet.versions
    :refer [as-version-vector nilable-version-vector? nilable-version-spec?
            version-less version-matches? version-range? version-spec?
            version-vector?]]))

(defn version-spec-more-specific
  "Compare the specificity of two version vectors."
  [spec1 spec2]
  (cond
   ;; one arg is nil
   (and (nil? spec1) (not (nil? spec2))) true
   (and (nil? spec2) (not (nil? spec1))) false

   ;; both args are version vectors
   (and
    (version-vector? spec1)
    (version-vector? spec2)) (> (count spec1) (count spec2))

   ;; one arg is a version vector
   (and
    (version-vector? spec1)
    (not (version-vector? spec2))) true
   (and
    (version-vector? spec2)
    (not (version-vector? spec1))) false

   ;; both args are version ranges
   (and
    (version-range? spec1)
    (version-range? spec2))
   (let [[from1 to1] spec1
         [from2 to2] spec2]
     (cond
      ;; open ended ranges
      (and from1 to1 (or (nil? from2) (nil? to2))) true
      (and from2 to2 (or (nil? from1) (nil? to1))) false
      (and from1 (nil? to1) from2 (nil? to2)) (version-less from2 from1)
      (and (nil? from1) to1 (nil? from2) to2) (version-less to1 to2)
      ;; range open above treated as more specific than range open below
      (and from1 (nil? to1) (nil? from2) to2) true
      (and (nil? from1) to1 from2 (nil? to2)) false
      ;; full comparison
      :else (or (version-less from2 from1)
                (and (= from1 from2) (version-less to1 to2)))))))

(defn ^{:internal true} match-less
  "A less for matches on a hierarchy and version."
  [hierarchy base-key version-key]
  (fn [i j]
    (let [osi (get i base-key)
          os-versioni (get i version-key)
          osj (get j base-key)
          os-versionj (get j version-key)
          ;; versioni (:version i)
          ;; versionj (:version j)
          ]
      (assert (version-spec? os-versioni))
      (assert (version-spec? os-versionj))
      (cond
       (and (isa? hierarchy osi osj) (not (isa? hierarchy osj osi))) true
       (and (isa? hierarchy osj osi) (not (isa? hierarchy osi osj))) false
       (version-spec-more-specific os-versioni os-versionj) true
       :else false))))

(defn ^{:internal true} os-match-less
  "A less for os, os-version and version matches."
  [hierarchy]
  (fn [i j]
    (let [osi (get i :os)
          os-versioni (get i :os-version)
          osj (get j :os)
          os-versionj (get j :os-version)
          versioni (:version i)
          versionj (:version j)]
      (assert (version-spec? os-versioni))
      (assert (version-spec? os-versionj))
      (cond
       (and (isa? hierarchy osi osj) (not (isa? hierarchy osj osi))) true
       (and (isa? hierarchy osj osi) (not (isa? hierarchy osi osj))) false
       (version-spec-more-specific os-versioni os-versionj) true
       (version-spec-more-specific os-versionj os-versioni) false
       :else (version-spec-more-specific versioni versionj)))))

;;; A map that is looked up based on os and os version. The key should be a map
;;; with :os and :os-version keys.
(defn ^{:internal true} lookup-os
  "Pass nil to default-value if non required"
  [base version hierarchy base-key version-key values default-value]
  (letfn [(matches? [v]
                     (let [i (key v)]
                       ;;  TODO switch back to invoking keyword when
                       ;;  core.typed supports it
                       (and (isa? hierarchy base (get i base-key))
                            (or
                             (not version)
                             (version-matches?
                              version
                              (get i version-key))))))]
    (if-let [[_ v] (first
                    (sort
                     (comparator
                      (fn[x y]
                        ((match-less hierarchy base-key version-key)
                         (key x) (key y))))
                     (filter matches? values)))]
      v
      (if-let [v (:default values)]
        v
        default-value))))

(declare version-map)

(deftype VersionMap [^clojure.lang.IPersistentMap data
                     hierarchy
                     ^clojure.lang.Keyword base-key
                     ^clojure.lang.Keyword version-key]
  clojure.lang.ILookup
  (valAt [m key]
    (if (map? key)
      (lookup-os
       ;; TODO switch back to invoking keyword when core.typed supports it
       (get key base-key)
       (get key version-key)
       hierarchy base-key version-key data nil)
      (:default data)))
  (valAt [m key default-value]
    (if (map? key)
      (lookup-os
       ;; TODO switch back to invoking keyword when core.typed supports it
       (get key base-key)
       (get key version-key)
       hierarchy base-key version-key data default-value)
      default-value))
  clojure.lang.IFn
  (invoke [m key]
    (if (map? key)
      (lookup-os
       (get key base-key)
       (get key version-key)
       hierarchy base-key version-key data nil)
      (:default data)))
  (invoke [m key default-value]
    (if (map? key)
      (lookup-os
       (get key base-key)
       (get key version-key)
       hierarchy base-key version-key data
       default-value)
      default-value))
  clojure.lang.IPersistentMap
  (assoc [m key val]
    (assert (map? key))
    (version-map hierarchy base-key version-key (assoc data key val)))
  (assocEx [m key val]
    (assert (map? key))
    (version-map hierarchy base-key version-key (assoc data key val)))
  (without [m key]
    (version-map hierarchy base-key version-key (dissoc data key))))

(defn version-map
  "Construct a version map. The keys should be maps with `base-key` and
`version-key` keys. The `base-key` value should be take from the
`hierarchy`. The `version-key` value should be a version vector, or a version
range vector."
  ([hierarchy base-key version-key os-value-pairs]
     (VersionMap. os-value-pairs hierarchy base-key version-key))
  ([hierarchy base-key version-key]
     (VersionMap. {} hierarchy base-key version-key)))
