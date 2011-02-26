(ns pallet.resource.test-resource
  (:use
   [pallet.resource :only [defresource defaggregate]]))

(defaggregate test-resource
  (apply-test-resources
   [request]
   (str
    "test-resource:"
    (-> request :group-node :tag)
    (-> request :group-node :image))))

(defresource test-component
  (test-component-fn
   [request arg]
   (str arg)))
