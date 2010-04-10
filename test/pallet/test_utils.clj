(ns pallet.test-utils
  (:import
   org.jclouds.compute.domain.internal.NodeMetadataImpl
   org.jclouds.compute.domain.NodeState))


(defmacro with-private-vars [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context.  From users mailing
list, Alan Dipert and MeikelBrandmeyer."
  `(let ~(reduce #(conj %1 %2 `@(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

