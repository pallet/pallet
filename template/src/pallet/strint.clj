(ns pallet.strint
  "Runtime string interpolation built on top of clojure.contrib.strint."
  (:require
   [clojure.walk :refer [prewalk-replace]]))

(require 'pallet.common.strint)         ; prevent slamhound from removing this

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
                              (eval (prewalk-replace value-map x))
                              x)))
                  (#'pallet.common.strint/interpolate f))))
