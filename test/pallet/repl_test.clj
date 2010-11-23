(ns pallet.repl-test
  (:use clojure.test)
  (:require [pallet.repl :as repl]))

(deftest use-test
  (repl/use-pallet))
