(ns pallet.mock
  (:require
   [clojure.contrib.condition :as condition])
  (:use clojure.test))

(def *expectations*)

(defn verify-expectations
  [checks]
  (doseq [check checks]
    (check)))

(defmacro once
  "Add an expectation that the function is called once."
  [v args body]
  `(let [counter# (atom 0)]
     (set! *expectations* (conj
                     *expectations*
                     (fn []
                       (is (= @counter# 1)
                         (format
                           "Expected one call to %s. %d seen."
                           '~v @counter#)))))
     (fn [& args#]
       (swap! counter# inc)
       (apply (fn ~args ~@(rest body)) args#))))

(defn construct-mock
  "Construct the mock. Checks for a mock wrapper around the body."
  [[v args body]]
  (if (and (list? body) (#{#'once} (resolve (first body))))
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

(defmacro expects
  "Binds a list of mocks, checling any expectations on exit of the block."
  [mocks & body]
  `(with-expectations
     (binding ~(construct-bindings mocks)
       ~@body)
     (verify-expectations *expectations*)))
