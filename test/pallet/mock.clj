(ns pallet.mock
  (:require
   [clojure.contrib.condition :as condition])
  (:use clojure.test))

(def *expectations*)

(defn verify-checks
  [checks]
  (doseq [check checks]
    (check)))

(defmacro once [v args body]
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
  (println "construct-mock " v args body (type body))
  (if (list? body)
    (println "  first "
             (first body)
             (type (first body))
             (namespace (first body))
             ))
  (if (and (list? body) (#{#'once} (resolve (first body))))
    `(~(first body) ~v ~args ~body)
    `(fn ~args ~@(if (seq? body) body (list body)))))

(defn add-mock [mocks mock]
  (println "add-mock " mock (first mock) (rest mock))
  (concat mocks [(first mock) (construct-mock mock)]))

(defn construct-bindings [mocks]
  (vec (reduce add-mock [] mocks)))

(defmacro with-expectations
  [& body]
  `(binding [*expectations* []]
     ~@body))

(defmacro expects [mocks & body]
  `(with-expectations
     (binding ~(construct-bindings mocks)
       ~@body)
     (verify-checks *expectations*)))

(defmacro mock [args & body]
  `(fn ~args ~@body))

