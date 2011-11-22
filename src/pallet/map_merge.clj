(ns pallet.map-merge
  "Map merging algorithms. Enables specification of merging function on a
   per key basis."
  (:require
   [clojure.set :as set]
   [pallet.common.map-utils :as map-utils]))

(defmulti merge-key
  "Merge function that dispatches on the map entry key"
  (fn [algorithms key val-in-result val-in-latter]
    (algorithms key :deep-merge)))

(defn merge-keys
  "Returns a map that consists of the rest of the maps conj-ed onto
  the first.  If a key occurs in more than one map, the mapping(s)
  from the latter (left-to-right) will be combined with the mapping in
  the result by calling:
    (merge-key key-algorithms key val-in-result val-in-latter)."
  [key-algorithms & maps]
  (when (some identity maps)
    (let [merge-entry (fn [m e]
                        (let [k (key e) v (val e)]
                          (if (contains? m k)
                            (assoc m k (merge-key key-algorithms k (get m k) v))
                            (assoc m k v))))
          merge2 (fn [m1 m2]
                   (reduce merge-entry (or m1 {}) (seq m2)))]
      (reduce merge2 maps))))

(defmethod merge-key :replace
  [_ _ val-in-result val-in-latter]
  val-in-latter)

(defmethod merge-key :merge
  [_ _ val-in-result val-in-latter]
  (merge val-in-result val-in-latter))

(defmethod merge-key :deep-merge
  [_ _ val-in-result val-in-latter]
  (let [map-or-nil? (fn [x] (or (nil? x) (map? x)))]
    (map-utils/deep-merge-with
     (fn deep-merge-env-fn [x y]
       (if (and (map-or-nil? x) (map-or-nil? y))
         (merge x y)
         (or y x)))
     val-in-result val-in-latter)))

(defmethod merge-key :merge-comp
  [_ _ val-in-result val-in-latter]
  (merge-with comp val-in-latter val-in-result))

(defmethod merge-key :merge-union
  [_ _ val-in-result val-in-latter]
  (set/union val-in-result val-in-latter))

(defmethod merge-key :concat
  [_ _ val-in-result val-in-latter]
  (concat val-in-result val-in-latter))

(defmethod merge-key :concat-distinct
  [_ _ val-in-result val-in-latter]
  (distinct (concat val-in-result val-in-latter)))
