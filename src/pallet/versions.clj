(ns pallet.versions
 "Version handling for pallet"
 (:require
  [clojure.string :as string]))

(defn version-vector
  "Convert a dotted version string to a vector of version numbers.
E.g.,
    (version-vector \"1.2\") => [1 2]"
  [version-string]
  (vec (map read-string (string/split version-string #"\."))))

(defn as-version-vector
  "Take a version, as either a string or a version vector, and returns a
version vector."
  [version]
  (if (string? version) (version-vector version) version))

(defn version-string
  "Convert a a vector of version numbers to a dotted version string.
E.g.,
    (version-vector [1 2]) => \"1.2\""
  [version-vector]
  {:pre [(seq version-vector)]}
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
    (cond
      (and (not v1) (not v2)) false
      (and v1 (not v2)) false
      (or (and (not v1) v2) (< (first v1) (first v2))) true
      (> (first v1) (first v2)) false
      :else (recur (next v1) (next v2)))))

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
    (number? (first spec)) (version-matches-version? version spec)
    (vector? spec) (let [[from to] spec]
                     (and (or (nil? from)
                              (not
                               (version-less version from)))
                          (or (nil? to)
                              (not
                               (version-less to version)))))
    (nil? spec) true))

(defn version-spec-less
  [spec1 spec2]
  (cond
    (number? (first spec1)) (if (number? (first spec2))
                              (> (count spec1) (count spec2))
                              true)
    (number? (first spec2)) false
    :else (let [[from1 to1] spec1
                [from2 to2] spec2]
            (and (not (version-less from1 from2))
                 (not (version-less to2 to1))
                 (not= spec1 spec2)))))
