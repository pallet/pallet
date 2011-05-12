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
   property or `*cleanup-nodes*`.

   The image list to be used can be selected using `pallet.test.image-list`
   and should specify one of the keys in `image-lists`."
  (:require
   [pallet.core :as core]
   [pallet.compute :as compute]
   [clojure.string :as string]))

(def
  ^{:doc "The default images for testing"}
  default-images
  [{:os-family :ubuntu :os-version-matches "10.04" :os-64-bit false
    :prefix "u1004"}
   {:os-family :ubuntu :os-version-matches "10.10" :os-64-bit true
    :prefix "u1010"}
   {:os-family :debian :os-version-matches "5.0.7" :os-64-bit false
    :prefix "deb"}
   {:os-family :centos :os-version-matches "5.5" :os-64-bit true
    :prefix "co55"}
   {:os-family :centos :os-version-matches "5.3" :os-64-bit false
    :prefix "co53"}
   {:os-family :arch :os-version-matches "2010.05" :os-64-bit true
    :prefix "arch"}
   {:os-family :fedora :os-version-matches "14" :os-64-bit true
    :prefix "f14"}])

(def
  ^{:doc "Selectable image lists"}
  image-lists
  {:amzn-linux [{:os-family :amzn-linux :os-64-bit true}]
   :aws-ubuntu-10-10 [{:os-family :ubuntu :image-id "ami-08f40561"}]
   ;; individual images from default-images
   :ubuntu-lucid [{:os-family :ubuntu :os-version-matches "10.04"
                   :os-64-bit false}]
   :ubuntu-maverick [{:os-family :ubuntu :os-version-matches "10.10"
                      :os-64-bit true}]
   :debian-lenny [{:os-family :debian :os-version-matches "5.0.7"
                   :os-64-bit false}]
   :centos-5-3 [{:os-family :centos :os-version-matches "5.3"
                 :os-64-bit false}]
   :centos-5-5 [{:os-family :centos :os-version-matches "5.5"
                 :os-64-bit true}]
   :arch-2010-05 [{:os-family :arch :os-version-matches "2010.05"
                   :os-64-bit true}]
   :fedora-14 [{:os-family :fedora :os-version-matches "14"
                :os-64-bit true}]})

(defn- read-property
  "Read a system property as a clojure value."
  [property-name]
  (when-let [property (System/getProperty property-name)]
    (if (string? property)
      (read-string property)
      property)))

(def ^{:doc "Guard execution of the live tests. Used to enable the tests."}
  *live-tests*
  (read-property "pallet.test.live"))

(def ^{:doc "Name used to find the service in config.clj or settings.xml."}
  service-name
  (if-let [name (System/getProperty "pallet.test.service-name")]
    (keyword name)
    :live-test))

(def ^{:doc "Flag to control cleanup of generated nodes"}
  *cleanup-nodes*
  (let [cleanup (read-property "pallet.test.cleanup-nodes")]
    (if (nil? cleanup) true cleanup)))

(def ^{:doc "Flag for tests in parallel"}
  *parallel*
  (let [parallel (System/getProperty "pallet.test.parallel")]
    (if (string/blank? parallel)
      false
      (read-string parallel))))

(def ^{:doc "List of images to test with" :deprecated "0.4.17"}
  *images*
  (let [image-list (System/getProperty "pallet.test.image-list")]
    (if (string/blank? image-list)
      default-images
      ((keyword image-list) image-lists))))

(defn images
  "List of images to test with"
  []
  (let [image-list (System/getProperty "pallet.test.image-list")]
    (if (string/blank? image-list)
      default-images
      ((keyword image-list) image-lists))))

(defn add-image-list!
  "Add an image list"
  [kw image-maps]
  (alter-var-root #'image-lists assoc kw image-maps))

(defn exclude-images
  "Takes two maps, and returns the first map with all entries removed that match
   one of the reject-images-list."
  [image-list reject-images-list]
  (remove
   (fn [image]
     (some
      (fn [reject] (every? #(= ((key %) image) (val %)) reject))
      reject-images-list))
   image-list))

(defn filter-images
  "Takes two maps, and returns the first map with only the entries that match
   one of the accept-images-list."
  [image-list accept-images-list]
  (filter
   (fn [image]
     (some
      (fn [accept] (every? #(= ((key %) image) (val %)) accept))
      accept-images-list))
   image-list))

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

(defn- effective-group-name
  [group-name spec]
  (keyword (str (name group-name) (name (get-in spec [:image :prefix] "")))))

(defn- node-spec
  [[group-name spec]]
  (-> spec
      (assoc
          :base-group-name (keyword (name group-name))
          :group-name (effective-group-name group-name spec))
      (update-in [:image] dissoc :prefix)))

(defn node-types
  "Build node types according to the specs"
  [specs]
  (into {} (map #((juxt :group-name identity) (node-spec %)) specs)))

(defn- counts
  "Build a map of node defintion to count suitable for passing to `converge`."
  [specs]
  (into {} (map #(vector (node-spec %) (:count (val %))) specs)))

(defn build-nodes
  "Build nodes using the node-types specs"
  [service node-types specs]
  (let [counts (counts specs)]
    (select-keys
     (->>
      (core/converge counts :compute service)
      :all-nodes
      (group-by compute/group-name)
      (map #(vector (keyword (first %)) (second %)))
      (into {}))
     (keys node-types))))

(defn destroy-nodes
  "Build nodes using the phase and specs"
  [service group-names]
  (doseq [group-name group-names]
    (compute/destroy-nodes-in-group service (name group-name))))

(defmacro test-nodes
  "Top level testing macro.

  Declares a live test.  Requires three symbols:
  - `compute` bound to the compute service being used
  - `node-map` bound to a map from group to nodes running for that group
  - `node-types` a map from group to node definition for that group

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

(defmacro test-for
  "Loop over tests, in parallel or serial, depending on pallet.test.parallel."
  [[& bindings] & body]
  `(when *live-tests*
     (if *parallel*
       (doseq [f# (doall (for [~@bindings] (future ~@body)))] @f#)
       (doseq [~@bindings] ~@body))))
