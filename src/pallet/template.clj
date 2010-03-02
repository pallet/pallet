(ns #^{:doc "Template file writing"}
  pallet.template
  (:require [clojure.contrib.str-utils2 :as string])
  (:use [pallet.stevedore :only [script]]
        [clojure.contrib.logging]))

(defmacro deftemplate [template [& args] m]
  `(defn ~template [~@args]
     ~m))

(defn- apply-template-file [[file-spec content]]
  (trace (str "apply-template-file " file-spec \newline content))
  (let [path (:path file-spec)]
    (string/join ""
                 (filter (complement nil?)
                         [(script (var file ~path) (cat > @file <<EOF))
                          content
                          "\nEOF\n"
                          (when-let [mode (:mode file-spec)]
                            (script (do (chmod ~mode @file))))
                          (when-let [group (:group file-spec)]
                            (script (do (chgrp ~group @file))))
                          (when-let [owner (:owner file-spec)]
                            (script (do (chown ~owner @file))))]))))

;; TODO - add chmod, owner, group
(defn apply-templates [template-fn args]
  (string/join "" (map apply-template-file (apply template-fn args))))
