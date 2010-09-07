(ns pallet.resource.test-resource
  (:use
   [pallet.resource :only [defresource defaggregate]]))

(defaggregate test-resource
  (apply-test-resources
   [request]
   (str
    "test-resource:"
    (:tag (:node-type request))
    (:image (:node-type request)))))

(defresource test-component
  (test-component-fn
   [request arg]
   (str arg)))
