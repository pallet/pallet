(ns pallet.task.add-service-test
  (:use
   clojure.test
   [clojure.java.io :only [file]]
   pallet.task.add-service
   pallet.test-utils))

(deftest add-service-file-test
  (let [service "xxx-pallet-test"
        f (file (System/getProperty "user.home")
                ".pallet" "services" (str service ".clj"))]
    (testing "id and pw"
      (try
        (add-service "xxx-pallet-test" "node-list" "id" "pw")
        (is (= {(keyword service)
                {:provider "node-list" :identity "id" :credential "pw"}}
               (read-string (slurp f))))
        (finally
          (.delete f))))
    (testing "id, pw and property"
      (try
        (add-service "xxx-pallet-test" "node-list" "id" "pw" "prop" "val")
        (is (= {(keyword service)
                {:provider "node-list" :identity "id" :credential "pw"
                 :prop "val"}}
               (read-string (slurp f))))
        (finally
          (.delete f))))
    (testing "id, pw and properties"
      (try
        (add-service
         "xxx-pallet-test" "node-list" "id" "pw" "prop" "val" :prop2 "val2")
        (is (= {(keyword service)
                {:provider "node-list" :identity "id" :credential "pw"
                 :prop "val" :prop2 "val2"}}
               (read-string (slurp f))))
        (finally
          (.delete f))))
    (testing "id, pw and property with url"
      (try
        (add-service
         "xxx-pallet-test" "node-list" "id" "pw" "prop" "http://abc.com/a")
        (is (= {(keyword service)
                {:provider "node-list" :identity "id" :credential "pw"
                 :prop "http://abc.com/a"}}
               (read-string (slurp f))))
        (finally
          (.delete f))))))
