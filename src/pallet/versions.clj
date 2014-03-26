(ns pallet.versions
  "Version handling for pallet"
  (:require
   [clojure.string :as string]
   [taoensso.timbre :refer [warnf]]))

(defn read-version-number
  "Read a version number from a string, ignoring alphabetic chars."
  [s]
  (try
    (Integer/parseInt (string/replace s #"[a-zA-Z-_]" ""))
    (catch Exception e
      (warnf "Could not obtain an integer from version component '%s'. %s"
             s (.getMessage e)))))

(defn version-vector
  "Convert a dotted (or dashed) version string to a vector of version numbers.
E.g.,
    (version-vector \"1.2\") => [1 2]"
  [version-string]
  (let [v (->>
           (string/split version-string #"[\.-]")
           (map read-version-number)
           (filterv identity))]
    (assert (every? number? v))
    v))

(defn version-vector?
  "Predicate to check for a version vector."
  [x]
  (if (and (vector? x)
           (every? number? x)
           (seq x))
    true
    false))

(defn nilable-version-vector?
  "Predicate to check for a version vector."
  [x]
  (if (or (nil? x)
          (and (vector? x)
               (every? number? x)
               (seq x)))
    true
    false))

(defn version-range?
  "Predicate to check for a version range."
  [x]
  (if (and (vector? x)
           (every? (some-fn version-vector? nil?) x)
           (seq x))
    true
    false))

(defn version-spec?
  "Predicate to check for a version spec."
  [x]
  (or (version-vector? x) (version-range? x)))

(defn nilable-version-spec?
  "Predicate to check for a version spec."
  [x]
  (or (nil? x)(version-spec? x)))

(defn as-version-vector
  "Take a version, as either a string or a version vector, and returns a
version vector."
  [version]
  (if (string? version) (version-vector version) version))

(defn version-string
  "Convert a vector of version numbers to a dotted version string.
E.g.,
    (version-vector [1 2]) => \"1.2\""
  [version-vector]
  {:pre [(version-vector? version-vector)]}
  (string/join "." version-vector))

(defn as-version-string
  "Take a version, as either a string or a version vector, and returns a
version string."
  [version]
  (if (string? version) version (version-string version)))

(defn version-less
  "Compare two version vectors."
  [v1 v2]
  (loop [v1 (seq v1)
         v2 (seq v2)]
    (let [fv1 (first v1)
          fv2 (first v2)]
      (cond
       (and (not v1) (not v2)) false
       (and v1 (not v2)) false
       (or (and (not v1) v2)
           (and fv1 fv2 (< fv1 fv2))) true
       (and fv1 fv2 (> fv1 fv2)) false
       :else (recur (next v1) (next v2))))))

(defn- version-matches-version?
  "Does the version match a single version spec"
  [version spec-version]
  (loop [v1 (seq version)
         v2 (seq spec-version)]
    (cond
     (and (not v1) (not v2)) true
     (and v1 (not v2)) true
     (or (and (not v1) v2) (not= (first v1) (first v2))) false
     :else (recur (next v1) (next v2)))))

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
