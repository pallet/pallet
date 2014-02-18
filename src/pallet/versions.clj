(ns pallet.versions
  "Version handling for pallet"
  (:require
   [clojure.core.typed :refer [ann loop> Nilable NilableNonEmptySeq]]
   [clojure.string :as string]
   [pallet.core.types :refer [VersionVector VersionRange VersionSpec]]))

(ann version-vector [String -> VersionVector])
(defn version-vector
  "Convert a dotted (or dashed) version string to a vector of version numbers.
E.g.,
    (version-vector \"1.2\") => [1 2]"
  [version-string]
  (let [v (map read-string (string/split version-string #"\.-"))]
    (assert (every? number? v))
    (vec v)))

;; (ann version-vector?
;;      [Any -> boolean
;;       :filters {:then (is VersionVector 0), :else (! VersionVector 0)}])

;; TODO - fix the no-check on these
(ann ^:no-check version-vector? (predicate VersionVector))
(defn version-vector?
  "Predicate to check for a version vector."
  [x]
  (if (and (vector? x)
           (every? number? x)
           (seq x))
    true
    false))

(ann ^:no-check nilable-version-vector? (predicate (U nil VersionVector)))
(defn nilable-version-vector?
  "Predicate to check for a version vector."
  [x]
  (if (or (nil? x)
          (and (vector? x)
               (every? number? x)
               (seq x)))
    true
    false))

(ann ^:no-check version-range? (predicate VersionRange))
(defn version-range?
  "Predicate to check for a version range."
  [x]
  (if (and (vector? x)
           (every? (some-fn version-vector? nil?) x)
           (seq x))
    true
    false))

(ann ^:no-check version-spec? (predicate VersionSpec))
(defn version-spec?
  "Predicate to check for a version spec."
  [x]
  (or (version-vector? x) (version-range? x)))

(ann ^:no-check nilable-version-spec? (predicate (U nil VersionSpec)))
(defn nilable-version-spec?
  "Predicate to check for a version spec."
  [x]
  (or (nil? x)(version-spec? x)))

(ann as-version-vector [(U String VersionVector) -> VersionVector])
(defn as-version-vector
  "Take a version, as either a string or a version vector, and returns a
version vector."
  [version]
  (if (string? version) (version-vector version) version))

(ann version-string [VersionVector -> String])
(defn version-string
  "Convert a vector of version numbers to a dotted version string.
E.g.,
    (version-vector [1 2]) => \"1.2\""
  [version-vector]
  {:pre [(version-vector? version-vector)]}
  (string/join "." version-vector))

(ann as-version-string [(U String VersionVector) -> String])
(defn as-version-string
  "Take a version, as either a string or a version vector, and returns a
version string."
  [version]
  (if (string? version) version (version-string version)))

(ann ^:no-check version-less ; TODO get this to type check without blowing up
     [(U nil VersionVector) (U nil VersionVector) -> boolean])
(defn version-less
  "Compare two version vectors."
  [v1 v2]
  (loop> [v1 :- (NilableNonEmptySeq Number) (seq v1)
          v2 :- (NilableNonEmptySeq Number) (seq v2)]
    (let [fv1 (first v1)
          fv2 (first v2)]
      (cond
       (and (not v1) (not v2)) false
       (and v1 (not v2)) false
       (or (and (not v1) v2)
           (and fv1 fv2 (< fv1 fv2))) true
           (and fv1 fv2 (> fv1 fv2)) false
           :else (recur (next v1) (next v2))))))

(ann version-matches-version? [VersionVector VersionVector -> boolean])
(defn- version-matches-version?
  "Does the version match a single version spec"
  [version spec-version]
  (loop> [v1 :- (NilableNonEmptySeq Number) (seq version)
          v2 :- (NilableNonEmptySeq Number) (seq spec-version)]
    (cond
     (and (not v1) (not v2)) true
     (and v1 (not v2)) true
     (or (and (not v1) v2) (not= (first v1) (first v2))) false
     :else (recur (next v1) (next v2)))))

(ann version-matches?
     [VersionVector (Nilable VersionSpec) -> (U boolean nil)])
(defn version-matches?
  "Predicate to test if a version matches a version spec. A version spec is a
   version, or two (possibly nil) versions in a vector, to specify a version
   range."
  [version spec]
  (cond
   (version-vector? spec) (version-matches-version? version spec)
   (version-range? spec) (let [[from to] spec]
                           (and (or (nil? from)
                                    (not
                                     (version-less version from)))
                                (or (nil? to)
                                    (not
                                     (version-less to version)))))
   (nil? spec) true))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (loop> 1))
;; End:
