(ns pallet.script-test
  "Simple script functions for testing."
  (:require
   [clojure.string :as string])
  (:use
   [pallet.stevedore :only [script]]))

(defmacro testing-script
  "Top level test form"
  [test-name & body]
  `(string/join
    \newline
    [(script
      (println (quoted ~test-name))
      (~'let "failcount" 0))
     ~@body
     (script
      (when-not (= 0 (deref "failcount"))
        (println "' '")
        (println (deref "failcount") " test failures")
        (~'exit 1)))]))

(defmacro is=
  "Test for equality"
  ([expected actual msg]
     `(script
       (var "expected" ~expected)
       (var "actual" ~actual)
       (when-not (= (deref "expected") (deref "actual"))
         (println ~(if msg (str "FAIL: " msg) "FAIL"))
         (println (str "'  Expected \\(= " ~expected " " ~actual "\\)'"))
         (println (str "'  Actual   ' \\(not \\(= "
                       (deref "expected") " "
                       (deref "actual") "\\)\\)"))
         (let "failcount" (+ "failcount" 1)))))
  ([expected actual]
     `(is= expected actual nil)))

(defmacro is-true
  "Test for true (ie 0)"
  ([actual msg]
     `(script
       ("(" ~actual ")" )
       (var "actual" (deref "?"))
       (when-not (= (deref "actual") "0")
         (println ~(if msg (str "FAIL: " msg) "FAIL"))
         (println (str "'  Expected " ~actual "'"))
         (println (str "'  Actual   '" (deref "actual")))
         (let "failcount" (+ "failcount" 1)))))
  ([actual]
     `(is actual nil)))
