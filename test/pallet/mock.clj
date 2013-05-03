(ns pallet.mock
  (:require
   [clojure.test :refer :all]
   [pallet.utils :as utils]))

(def ^{:dynamic true} *expectations*)

(defn equality-checker
  [actual expected msg]
  (is (= actual expected) msg))

(def ^{:dynamic true} *equality-checker* equality-checker)

(defn verify-expectations
  [checks]
  (doseq [check checks]
    (check)))

(defn add-expectation
  "Add an expectation check function to the list of expectations"
  [f]
  (set! *expectations* (conj *expectations* f)))

(defmacro once
  "Add an expectation that the function is called once."
  [v args body]
  `(let [counter# (atom 0)]
     (add-expectation
      (fn []
        (*equality-checker*
         @counter# 1
         (format "Expected one call to %s. %d seen." '~v @counter#))))
     (fn [& args#]
       (swap! counter# inc)
       (apply (fn ~args ~@(rest body)) args#))))

(defmacro times
  "Add an expectation that the function is called specified number of times."
  [v args body]
  `(let [counter# (atom 0)
         n# ~(second body)]
     (add-expectation
      (fn []
        (*equality-checker*
         @counter# n#
         (format "Expected %d calls to %s. %d seen." n# '~v @counter#))))
     (fn [& args#]
       (swap! counter# inc)
       (apply (fn ~args ~@(nnext body)) args#))))

(defn construct-mock
  "Construct the mock. Checks for a mock wrapper around the body."
  [[v args body]]
  (if (and (list? body) (#{#'once #'times} (resolve (first body))))
    `(~(first body) ~v ~args ~body)
    `(fn ~args ~@(if (seq? body) body (list body)))))

(defn add-mock
  "Add a mock to the bindings."
  [mocks mock]
  (concat mocks [(first mock) (construct-mock mock)]))

(defn construct-bindings
  "Construct a binding vector from the mock specification."
  [mocks]
  (vec (reduce add-mock [] mocks)))

(defmacro with-expectations
  [& body]
  `(binding [*expectations* []]
     ~@body))

(defmacro ^{:requires [#'utils/with-redef]}
  expects
  "Binds a list of mocks, checling any expectations on exit of the block."
  [mocks & body]
  `(with-expectations
     (utils/with-redef ~(construct-bindings mocks)
       ~@body)
     (verify-expectations *expectations*)))
