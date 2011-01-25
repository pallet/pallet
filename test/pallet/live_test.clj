(ns pallet.live-test
  "Helpers for live testing.
   This is based on creating nodes, running a body, and then removing the nodes.
   There is provision for controlling the running of tests based on rebindable
   vars initialised wih system properties.

   Tests are disabled by default, and can be enabled by setting the
   `pallet.test.live` property, or binding `*live-tests*` to true.

   The compute service to use can be configured with the
   `pallet.test.service-name` property. The default is :live-test.

   Cleanup of nodes can be suppressed with the `pallet.test.cleanup-nodes`
   property or `*cleanup-nodes*`."
  (:require
   [pallet.core :as core]
   [pallet.compute :as compute]
   [pallet.resource :as resource]))

(def ^{:doc "Guard execution of the live tests. Used to enable the tests."}
  *live-tests*
  (System/getProperty "pallet.test.live"))

(def ^{:doc "Name used to find the service in config.clj or settings.xml."}
  service-name
  (if-let [name (System/getProperty "pallet.test.service-name")]
    (keyword name)
    :live-test))

(def ^{:doc "Flag to control cleanup of generated nodes"}
  *cleanup-nodes*
  (let [cleanup (System/getProperty "pallet.test.cleanup-nodes")]
    (if (nil? cleanup) true cleanup)))

(defn set-live-tests!
  "Globally switch live-test on or off."
  [flag]
  (alter-var-root #'*live-tests* (constantly flag)))

(defmacro with-live-tests
  "Force live tests within `body` to be executed."
  [& body]
  `(binding [*live-tests* true]
     ~@body))

(defmacro with-no-cleanup
  "Prevent live tests from cleaning up the nodes it creates."
  [& body]
  `(binding [*cleanup-nodes* false]
     ~@body))

(def ^{:doc "Specifies the service to use with testing."}
  service (atom nil))

(defn set-service!
  "Set the compute service to use with live-test."
  [compute]
  (reset! service compute))

(defn find-service
  "Retrieve the compute service to be used."
  []
  (or
   @service
   (set-service! (compute/compute-service-from-config-file service-name))
   (set-service! (compute/compute-service-from-settings service-name))))

(defn node-types
  "Build node types according to the specs"
  [specs]
  (into
   {}
   (map #(vector (key %) (assoc (val %) :tag (keyword (key %)))) specs)))

(defn- counts
  "Build a map of node defintion to count suitable for passing to `converge`."
  [specs]
  (into
   {}
   (map
    #(vector (assoc (val %) :tag (keyword (name (key %)))) (:count (val %)))
    specs)))


(defn build-nodes
  "Build nodes using the node-types specs"
  [service node-types specs]
  (let [counts (counts specs)]
    (select-keys
     (->>
      (core/converge counts :compute service)
      :all-nodes
      (group-by compute/tag)
      (map #(vector (keyword (first %)) (second %)))
      (into {}))
     (keys node-types))))

(defn destroy-nodes
  "Build nodes using the phase and specs"
  [service tags]
  (doseq [tag tags]
    (compute/destroy-nodes-with-tag service (name tag))))

(defmacro test-nodes
  "Top level testing macro.

  Declares a live test.  Requires three symbols:
  - `compute` bound to the compute service being used
  - `node-map` bound to a map from tag to nodes running for that tag
  - `node-types` a map from tag to node definition for that tag

  `specs` is a map, keyed by tag, with values being a map with
  `:image`, `:phases` and `:count` tags

  This example installs tomcat and tests that the port is open:
        (test-nodes [compute node-map node-types]
          {:tag {:image {:os-family :ubuntu} :count 1}}
          (lift mynode :phase :verify :compute compute))"
  [[compute node-map node-types] specs & body]
  `(when *live-tests*
     (let [~compute (find-service)
           ~node-types (node-types ~specs)]
       (try
         (let [~node-map (build-nodes ~compute ~node-types ~specs)]
           ~@body)
         (finally
          (when *cleanup-nodes*
            (destroy-nodes ~'compute (keys ~'node-types))))))))
