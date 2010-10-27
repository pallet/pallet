(ns pallet.live-test
  "Helpers for live testing.
   This is based on having a set of phases, that are excuted for a set of
   specs"
  (:require
   [pallet.core :as core]
   [pallet.compute :as compute]
   [pallet.resource :as resource]
   [pallet.crate.automated-admin-user :as automated-admin-user]))

(def *live-tests* (System/getProperty "pallet.test.live"))

(defn set-live-tests!
  "Globally switch live-test on or off."
  [flag]
  (alter-var-root #'*live-tests* (constantly flag)))

(def service (atom nil))

(defn set-service!
  "Set the compute service to use with live-test."
  [compute]
  (reset! service compute))

(defn find-service
  []
  (or
   @service
   (set-service! (pallet.compute/compute-service-from-settings))))

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
  "Build nodes using the node-types specs"
  [service node-types specs]
  (let [counts (counts node-types specs)]
    [(select-keys
      (->>
       (core/converge counts :compute service)
       :all-nodes
       (group-by compute/tag)
       (map #(vector (keyword (first %)) (second %)))
       (into {}))
      (keys node-types))
     node-types]))

(defn destroy-nodes
  "Build nodes using the phase and specs"
  [service tags]
  (doseq [tag tags]
    (compute/destroy-nodes-with-tag service (name tag))))

(defmacro with-nodes
  [[compute node-map node-types phases specs] & body]
  `(when *live-tests*
     (let [~compute (find-service)
           ~node-types (node-types ~phases ~specs)]
       (try
         (let [~node-map (build-nodes ~compute ~node-types ~specs)]
           ~@body)
         (finally
          (destroy-nodes ~'compute (keys ~'node-types)))))))
