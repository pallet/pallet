(ns pallet.core.api-builder-test
  (:require
   [clojure.test :refer :all]
   [pallet.core.api-builder :refer [defn-api]]
   [schema.core :as schema]))

(defn-api xxxx
  {:sig [[schema.core/Any :- schema.core/Any]]}
  [x] x)
