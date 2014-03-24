(ns pallet.test-env
  "Provide a test environment for live tests.

The `test-env` macro is expected to be used in a test namespace, and
will provide the *compute-service* and *node-spec-meta* bindings.

It uses information from a node-spec-metas map and the leiningen project map
(made available via configleaf) to iterate all tests in the namespace
over a sequence of node-specs.  The node-spec is available in tests as
(:node-spec *node-spec-metas*)."
  (:require
   [clj-time.core :refer [now]]
   [clj-time.format :refer [formatters unparse]]
   [clojure.java.io :as io]
   [clojure.set :refer [intersection]]
   [clojure.test :as test]
   [com.palletops.multi-test :as multi-test]
   [pallet.compute
    :refer [compute-service? instantiate-provider service-properties]]
   [pallet.configure :as configure]
   [pallet.utils :refer [apply-map]]))


;;; # Dynamic vars used in configuration and test-env bindings
(def ^:dynamic *compute-service*
  "The compute service to use.  May be bound to a service keyword, a
  service map or a compute service instance.  Inside a test, it will
  be bound to a compute service instance."
  nil)

(def ^:dynamic *selectors*
  "Can be set with selectors! to provide a set of keyword selectors to
  use."
  nil)

(def ^:dynamic *node-spec-meta*
  "Inside tests, bound to the current node-spec-meta map.")

;;; # Test result formatters
(def types-hierarchy
  (-> (make-hierarchy)
      (derive :pass :expected-fail)
      (derive :pass :expected-error)
      (derive :fail :pass)
      (derive :error :fail)))

(defn- result-with-node-details
  [result]
  (-> result
      (merge (select-keys (:node-spec-meta result) [:name :selector]))
      (dissoc :node-spec-meta)))

(defn process-results
  "Convert test results into a format for output."
  [results]
  (let [results (->> results
                     (map
                      (fn [result]
                        (->
                         result
                         (dissoc :var :expected :message :contexts)))))
        groups (group-by
                #(select-keys % [:feature :service :selector])
                results)
        pass-fail (map
                   (fn [[k v]]
                     (merge
                      k
                      (reduce
                       (fn [res {:keys [result-type] :as m}]
                         (cond-> res
                                 (isa? types-hierarchy (:type m) (:type res))
                                 (->
                                  (assoc :type (:type m))
                                  (cond->
                                   result-type (assoc :result-type
                                                 result-type)))))
                       {:type :pass}
                       v)))
                   groups)
        ;; pass-fail (map result-with-expected pass-fail)
        ]
    pass-fail))

(defn longest-kw
  "Return the kw with the longest name"
  [kws]
  (keyword
   (reduce
    (fn [n v] (if (< (count n) (count v)) v n))
    (name (first kws)) (map name (rest kws)))))

(defn format-tests
 "Convert test results into a format for output."
 [results untested]
 (pr-str {:results (process-results results)
          :date (unparse (formatters :basic-date-time) (now))
          :untested (map (comp longest-kw :selectors) untested)}))

(defn output-results
  [results untested output-file]
  (spit output-file (format-tests results untested)))

;;; # Test reporter to add the value of the bound variables
(defmulti report :type)

(defmethod report :default [m]
  (multi-test/report m))

(defn add-vars [m]
  {:pre [(map? *node-spec-meta*)
         (:selector *node-spec-meta*)]}
  (as-> (multi-test/result-with-vars m) m
        (assoc m
          :service (:provider (service-properties *compute-service*))
          :expected-errors (:expected *node-spec-meta*)
          :selector (:selector *node-spec-meta*)
          :feature (concat [(-> m :var meta :name str)] (:contexts m)))))

(defn- remove-expected [m]
  (dissoc m :expected-errors))

(defmethod report :pass [m]
  (multi-test/report
   (-> m add-vars remove-expected)))

(defn not-supported?
  "Predicate to test for a not-supported exception."
  [result]
  (and (instance? clojure.lang.ExceptionInfo (:actual result))
       (:not-supported (ex-data (:actual result)))))

(defn expected?
  "Check to see if a fail or error result matches an item in
  the :expected sequence for the current test-spec."
  [{:keys [expected-errors feature] :as result}]
  {:pre [feature]}
  (some
   (fn [{:keys [expected? feature] :as exp}]
     {:pre [feature expected?]}
     (when (and (keyword? expected?) (not (#{:not-supported} expected?)))
       (throw (ex-info (str "Unknown expected? keyword, " expected?))))
     (let [f (if (= expected? :not-supported) not-supported? expected?)]
       (and (= feature (:feature exp))
            (f result)
            (or (and (keyword? expected?) expected?)
                true))))
   expected-errors))

(defn print-env [m]
  (when-not (= :pass (:type m))
    (test/with-test-out
      (println
       (:provider (service-properties *compute-service*))
       " "
       (longest-kw (:selectors *node-spec-meta*)))))
  m)

(defmethod report :fail [m]
  (as-> (add-vars m) m
        (cond-> m
                (expected? m) (assoc :type :pass
                                     :result-type
                                     (expected? m)))
        (remove-expected m)
        (print-env m)
        (multi-test/report m)))

(defmethod report :error [m]
  (as-> (add-vars m) m
        (cond-> m
         (expected? m) (assoc :type :pass
                              :result-type (expected? m)))
        (remove-expected m)
        (print-env m)
        (multi-test/report m)))

(defmethod report :summary [m]
  (test/with-test-out
    (println "summary" m)))

;;; # Set Configuration
(defn selectors
  "Return a set of selectors, either from the *selectors* var or from
  the configuration map passed as argument."
  [config]
  (or *selectors* (:selectors config)))

(defn compute-service
  "Return a compute-service spec, either from the *selectors* var or
  from the configuration map passed as argument."
  [config]
  (or *compute-service* (:service config)))

(defn compute-service!
  "Set the compute service spec.  Can be set to a service keyword,
  a service map, or a compute service instance."
  [service]
  {:pre [(or (nil? service) (keyword? service) (map? service)
             (compute-service? service))]}
  (alter-var-root #'*compute-service* (constantly service)))

(defn selectors!
  "Set the selectors to use (a set of keywords)."
  [selectors]
  {:pre [(or (nil? selectors) (set? selectors))]}
  (alter-var-root #'*selectors* (constantly selectors)))

(defn config-from-project
  "Return a config map from a leiningen project map, as written by
  configleaf, for example."
  [project-map]
  (:pallet/test-env project-map))

(defn matching-node-specs [node-spec-metas test-specs]
  (map
   (fn [{:keys [selector expected] :as test-spec}]
     {:pre [selector]}
     (let [matched (filter (fn [{:keys [selectors] :as node-spec}]
                             (if selectors
                               (selectors selector)))
                           node-spec-metas)]
       (when-not (= 1 (count matched))
         (throw (ex-info
                 (format "Selector %s matched %s node specs (1 expected) %s"
                         selector (count matched) (pr-str node-spec-metas))
                 {:count (count matched)})))
       (assoc (first matched) :expected expected :selector selector)))
   test-specs))

(defn unmatched-node-specs
  [node-spec-metas matched]
  (remove (set (map #(dissoc % :expected :selector) matched)) node-spec-metas))

;;; ## Node Spec Meta Maps

;;; Filters node-spec-meta maps.

;;; A node-spec-meta is a map with a :node-spec key, containing a
;;; node-spec.  It can also have a :selectors key with a set of
;;; keywords, a group-suffix key with a suffix string for node names,
;;; and a :name key with a string value.
(defn- matches-selectors?
  "Predicate for matching any of a set of keyword selectors with a
  node-spec meta map."
  [selectors node-spec-meta]
  {:pre [(set? selectors)]}
  (seq (intersection selectors (:selectors node-spec-meta))))

;;; # test-env for test namespaces
(defn test-ns-with-env
  "Test a namespace, ns, using the node-spec-metas map to provide node-specs,
  and a project-map to provide configuration of selectors and
  compute-service."
  [ns node-spec-metas project-map {:keys [output-file]
                                   :or {output-file "test-results.edn"}
                                   :as options}]
  (assert (map? project-map) "project-map not specified as a map")
  (let [config (and project-map (config-from-project project-map))
        test-specs (:test-specs config)]
    (assert (every? :selector test-specs)
            "Every test-spec must have a selector")
    (let [cs (compute-service config)
          service (cond
                   (keyword? cs) (configure/compute-service cs)
                   (map? cs) (apply-map instantiate-provider
                                        (:provider cs) (dissoc cs :provider))
                   :else cs)
          service-kw (if service (:provider (service-properties service)))
          nsms (if service (:variants (service-kw node-spec-metas)))
          mns (matching-node-specs nsms test-specs)
          selectors (selectors config)
          nsm (cond->> mns
                       (seq selectors) (filter
                                        #(matches-selectors? selectors %)))]
      (assert (every? :selector mns)
              "Every mns must have a selector")
      (assert (every? :selector nsm)
              "Every mns must have a selector")
      (when (seq selectors)
        (let [f (io/file output-file)]
          (when (.exists f)
            (.delete f))))
      (-> (multi-test/test-ns
           ns
           (merge
            {:bindings (map
                        (fn [cs nsm]
                          {#'*compute-service* cs #'*node-spec-meta* nsm})
                        (repeat service) nsm)
             :reporter report}
            (dissoc options :project-map)))
          (cond->           ; only write output if not using selectors
           (not (seq selectors)) (output-results
                                  (unmatched-node-specs nsms mns)
                                  output-file))))))

(defn test-env*
  [node-spec-metas project-map options]
  `(defn ~'test-ns-hook []
     (test-ns-with-env
      '~(ns-name *ns*) ~node-spec-metas ~project-map ~options)))

(defmacro test-env
  "Declare a test environment for clojure.test tests.
  node-spec-metas is a map keyed on provider keyword, with values
  of sequences of node-spec-meta maps.
  options is a map of options for multi-test/test-ns, with an extra
  :project-map key, which can be passed a leiningen configuration map
  (e.g. as stored by configleaf), where :pallet/test-env key
  specifies :service and :selectors keys."
  ([node-spec-metas project-map options]
     (test-env* node-spec-metas project-map options))
  ([node-spec-metas project-map]
     (test-env* node-spec-metas project-map {})))
