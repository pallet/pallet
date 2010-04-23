(ns pallet.maven-test
  (:use [pallet.maven] :reload-all)
  (:use
   clojure.test
   pallet.test-utils))

(deftest read-maven-settings-test
  (is (make-settings)))

;; (deftest credentials-test
;;   (let [props (credentials)]
;;     (is (= "cloudservers" (first props)))))
