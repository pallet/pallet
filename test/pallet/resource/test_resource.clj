(ns pallet.resource.test-resource
  (:use
   [pallet.target :only [admin-group tag template]]
   [pallet.stevedore :only [script]]
   [pallet.template]
   [pallet.resource :only [defresource defaggregate]]
   [pallet.resource.user :only [user-home]]
   [clojure.contrib.logging]))

(defn- apply-test-resources [args]
  (str "test-resource:" (tag) (template)))

(defaggregate test-resource
  apply-test-resources [])

(defn- test-component-fn [arg]
  (str arg))

(defresource test-component
  test-component-fn [arg])
