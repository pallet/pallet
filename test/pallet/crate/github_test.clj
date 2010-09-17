(ns pallet.crate.github-test
  (:use pallet.crate.github)
  (:require
   [pallet.resource :as resource])
  (:use clojure.test))

(deftest invoke-test
  (let [akey "key12344"]
    (binding [pallet.crate.github/api (fn [& _] {:public_keys [{:key akey}]})]
      (is
       (resource/build-resources
        []
        (deploy-key "project" "title" akey :username "u" :apikey "a")
        (deploy-key "project" "title" akey :username "u" :password "a")))
      (is
       (resource/build-resources
        [:parameters {:github {:username "u" :password "p"}}]
        (deploy-key "project" "title" akey)))
      (is
       (resource/build-resources
        [:parameters {:github {:username "u" :apikey "p"}}]
        (deploy-key "project" "title" akey)))
      (is (thrown?
           clojure.contrib.condition.Condition
           (resource/build-resources
            []
            (deploy-key "project" "title" akey)))))))
