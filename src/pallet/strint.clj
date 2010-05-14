(ns pallet.strint
 "Runtime string interpolation built on top of clojure.contrib.strint."
 (:use clojure.contrib.strint)
 (:require
   pallet.compat
   clojure.walk))

(pallet.compat/require-contrib)

(defmacro capture-values
  "Capture the values of the specified symbols in a symbol->value map."
  [& values]
  (into {} (map (fn [s] [ `'~s s]) values)))

(defn <<!
  "Interpolate a string given a map of symbol->value"
  [f value-map]
  (apply str
         (map (fn [x] (if (symbol? x)
                            (value-map x)
                            (if (seq x)
                              (eval (clojure.walk/prewalk-replace value-map x))
                              x)))
                  (#'clojure.contrib.strint/interpolate f))))
