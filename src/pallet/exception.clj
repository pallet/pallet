(ns pallet.exception
  "Exception functions"
  (:require
   [clojure.java.io :as io]))

(defn compiler-exception
  "Return a compiler exception.  This is should be used to throw in
  macros defining top level forms, as clojure doesn't wrap these in
  Compiler$CompilerException."
  [form msg data]
  (clojure.lang.Compiler$CompilerException.
   (.getName (io/file *file*))
   (or (-> form meta :line) 1)
   (or (-> form meta :column) 1)
   (if (seq data)
     (ex-info msg data)
     (Exception. msg))))
