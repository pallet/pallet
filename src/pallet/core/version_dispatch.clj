(ns pallet.core.version-dispatch
  "Version dispatch.

This is based on dispatching over a map.  The map has a `base` key, which
must be part of a `hierarchy`. The map also has a `version-key`, with a
value that must be a VersionSpec."
  (:require
   [clojure.core.typed
    :refer [ann ann-datatype letfn> fn> Hierarchy Map Nilable]]
   [pallet.core.type-annotations]
   [pallet.core.types
    :refer [assert-type-predicate assert-not-nil assert-object-or-nil
            Keyword MapEntry OsVersionMap VersionVector VersionSpec]]
   [pallet.versions
    :refer [as-version-vector version-matches? version-spec-less
            version-spec? version-vector?]]))

;; TODO: find out why this function takes forever to type check
(ann ^:no-check match-less [Hierarchy Keyword Keyword ->
                            [(Map Any Any) (Map Any Any) -> boolean]])
(defn ^{:internal true} match-less
  "A less for versions."
  [hierarchy base-key version-key]
  (fn> [i :- (Map Any Any) j :- (Map Any Any)]
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
       (version-spec-less os-versioni os-versionj) true
       (version-spec-less os-versionj os-versioni) false
       :else false ;; (version-spec-less versioni versionj)
       ))))

;;; A map that is looked up based on os and os version. The key should be a map
;;; with :os and :os-version keys.
(ann lookup-os
     [Keyword (Nilable VersionVector) Hierarchy Keyword Keyword
      (Map (Map Any Any) Any) Any -> Any])
(defn ^{:internal true} lookup-os
  "Pass nil to default-value if non required"
  [base version hierarchy base-key version-key values default-value]
  (letfn> [matches? :- [(clojure.lang.IMapEntry (Map Any Any) Any)
                        -> (U nil boolean)]
           (matches? [v]
                     (let [i (key v)]
                       ;;  TODO switch back to invoking keyword when
                       ;;  core.typed supports it
                       (and (isa? hierarchy base (get i base-key))
                            (or
                             (not version)
                             (version-matches?
                              (assert-not-nil version)
                              (assert-type-predicate
                               (get i version-key) version-spec?))))))]
    (if-let [[_ v] (first
                    (sort
                     (comparator
                      (fn> [x :- (clojure.lang.IMapEntry (Map (HMap) Any) Any)
                            y :- (clojure.lang.IMapEntry (Map (HMap) Any) Any)]
                           ((match-less hierarchy base-key version-key)
                            (key x) (key y))))
                     (filter matches? values)))]
      v
      (if-let [v (:default values)]
        v
        default-value))))

(declare version-map)

;; TODO
(ann ^:no-check os-version-map? (predicate OsVersionMap))
(defn os-version-map?
  [x]
  (map? x))


(ann-datatype ^:no-check
              VersionMap [data :- (Map (Map Any Any) Any)
                          hierarchy :- Hierarchy
                          base-key :- Keyword
                          version-key :- Keyword])
(deftype VersionMap [^clojure.lang.IPersistentMap data
                     hierarchy
                     ^clojure.lang.Keyword base-key
                     ^clojure.lang.Keyword version-key]
  clojure.lang.ILookup
  (valAt [m key]
    (if (map? key)
      (assert-object-or-nil
       (lookup-os
        ;; TODO switch back to invoking keyword when core.typed supports it
        (assert-type-predicate (get key base-key) keyword?)
        (assert-type-predicate (get key version-key) version-vector?)
        hierarchy base-key version-key data nil))
      (assert-object-or-nil (:default data))))
  (valAt [m key default-value]
    (if (map? key)
      (assert-object-or-nil
       (lookup-os
        ;; TODO switch back to invoking keyword when core.typed supports it
        (assert-type-predicate (get key base-key) keyword?)
        (assert-type-predicate (get key version-key) version-vector?)
        hierarchy base-key version-key data default-value))
      default-value))
  clojure.lang.IFn
  (invoke [m key]
    (if (map? key)
      (assert-object-or-nil
       (lookup-os
        ;; TODO switch back to invoking keyword when core.typed supports it
        (assert-type-predicate (get key base-key) keyword?)
        (assert-type-predicate (get key version-key) version-vector?)
        hierarchy base-key version-key data nil))
      (assert-object-or-nil (:default data))))
  (invoke [m key default-value]
    (if (map? key)
      (assert-object-or-nil
       (lookup-os
        ;; TODO switch back to invoking keyword when core.typed supports it
        (assert-type-predicate (get key base-key) keyword?)
        (assert-type-predicate (get key version-key) version-vector?)
        hierarchy base-key version-key data
        default-value))
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

(ann version-map
     (Fn [Hierarchy Keyword Keyword (Map (Map Any Any) Any) -> VersionMap]
         [Hierarchy Keyword Keyword -> VersionMap]))
(defn version-map
  "Construct a version map. The keys should be maps with `base-key` and
`version-key` keys. The `base-key` value should be take from the
`hierarchy`. The `version-key` value should be a version vector, or a version
range vector."
  ([hierarchy base-key version-key os-value-pairs]
     (VersionMap. os-value-pairs hierarchy base-key version-key))
  ([hierarchy base-key version-key]
     (VersionMap. {} hierarchy base-key version-key)))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (letfn> 1))
;; End:
