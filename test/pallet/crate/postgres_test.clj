(ns pallet.crate.postgres-test
  (:require
   [pallet.resource :as resource]
   [pallet.test-utils :as test-utils])
  (:use
   pallet.crate.postgres
   clojure.test))

(deftest postgres-test
  (is ; just check for compile errors for now
   (test-utils/build-resources
    []
    (postgres "8.0")
    (postgres "9.0")
    (hba-conf :records [])
    (postgresql-script "some script")
    (create-database "db")
    (create-role "user"))))
