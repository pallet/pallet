(ns pallet.live-test
  "Helpers for live testing.
   This is based on having a set of phases, that are excuted for a set of
   specs"
  (:require
   [pallet.core :as core]
   [pallet.compute :as compute]
   [pallet.resource :as resource]
   [pallet.crate.automated-admin-user :as automated-admin-user]))

(defn node-types
  [phases specs]
  (into
   {}
   (for [[tag {:keys [phases]}] phases
         :let [specs (tag specs)]]
     [tag (apply core/make-node
                 (name tag)
                 (:image specs)
                 (apply
                  concat
                  (merge
                   {:bootstrap (resource/phase
                                (automated-admin-user/automated-admin-user))}
                   phases)))])))

(defn counts
  [node-types specs]
  (into
   {}
   (map #(vector (% node-types) (-> specs % :count)) (keys node-types))))


(defn build-nodes
  "Build nodes using the phase and specs"
  [phases specs]
  (let [service (pallet.compute/compute-service-from-settings)
        node-types (node-types phases specs)
        counts (counts node-types specs)]
    [(select-keys
      (->>
       (core/converge counts :compute service)
       :all-nodes
       (group-by compute/tag)
       (map #(vector (keyword (first %)) (second %)))
       (into {}))
      (keys node-types))
     service]))

(defn destroy-nodes
  "Build nodes using the phase and specs"
  [service node-map]
  (doseq [[tag nodes] node-map]
    (compute/destroy-nodes-with-tag service (name tag))))

(def *live-tests* (System/getProperty "pallet.test.live"))

(defmacro with-nodes
  [[compute name phases specs] & body]
  `(when *live-tests*
     (let [[~name ~compute] (build-nodes ~phases ~specs)]
       ~@body
       (destroy-nodes ~'compute ~'name))))
