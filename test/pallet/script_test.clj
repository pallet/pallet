(ns pallet.script-test
  "Simple script functions for testing."
  (:require
   [clojure.string :as string]
   [pallet.script.lib :refer [exit]]
   [pallet.stevedore :refer [script]]))

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
      (if (= 0 (deref "failcount"))
        (println "Test" ~test-name "PASSED")
        (println "test" ~test-name "FAILED with" (deref "failcount") "failures")
        (exit 1)))]))

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
     `(is= ~expected ~actual nil)))

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
