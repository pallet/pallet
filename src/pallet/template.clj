(ns pallet.template
  "Template file writing"
  (:require [clojure.contrib.str-utils2 :as string])
  (:use [pallet.stevedore :only [script]]
        [pallet.resource.file]
        [pallet.core :only [node-type]]
        [clojure.contrib.duck-streams :only [slurp*]]
        [clojure.contrib.logging]))


(defn get-resource
  "Loads a resource. Returns a URI."
  [path]
  (-> (clojure.lang.RT/baseLoader) (.getResource path)))

(defn path-components
  "Split a path into directory, basename and extension components"
  [path]
  (let [f (java.io.File. path)
        filename (.getName f)
        i (.lastIndexOf filename "." )]
    [(.getParent f) (.substring filename 0 i) (.substring filename (inc i))]))

(defn pathname
  "Build a pathname from a list of path and filename parts.  Last part is assumed
   to be a file extension."
  [& parts]
  (str (apply str (interpose java.io.File/separator (butlast parts)))
       "." (last parts)))

(defn candidate-templates
  "Generate a prioritised list of possible template paths."
  [path tag template]
  (let [[dirpath base ext] (path-components path)]
    [(pathname dirpath (str base "_" tag) ext)
     (pathname (str "resource/" dirpath) (str base "_" tag) ext)
     path
     (str "resource/" path)]))

(defn find-template
  "Find a template for the specified path, for application to the given node.
   Templates may be specialised."
  [path node-type]
  (some
   get-resource
   (candidate-templates path (node-type :tag) (node-type :image))))





;;; programatic templates - umm not really templates at all

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
                            (script (do ("chmod" ~mode @file))))
                          (when-let [group (:group file-spec)]
                            (script (do ("chgrp" ~group @file))))
                          (when-let [owner (:owner file-spec)]
                            (script (do ("chown" ~owner @file))))]))))

;; TODO - add chmod, owner, group
(defn apply-templates [template-fn args]
  (string/join "" (map apply-template-file (apply template-fn args))))
