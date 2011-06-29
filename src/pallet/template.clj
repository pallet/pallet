(ns pallet.template
  "Template file writing"
  (:require
   [pallet.compute :as compute]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.strint :as strint]
   [pallet.target :as target]
   [pallet.utils :as utils]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]))

(defn get-resource
  "Loads a resource. Returns a URI."
  [path]
  (-> (clojure.lang.RT/baseLoader) (.getResource path)))

(defn path-components
  "Split a resource path into path, basename and extension components."
  [path]
  (let [p (inc (.lastIndexOf path "/"))
        i (.lastIndexOf path ".")]
    [(when (pos? p) (subs path 0 (dec p)))
     (if (neg? i) (subs path p) (subs path p i))
     (if (neg? i) nil (subs path (inc i)))]))

(defn pathname
  "Build a pathname from a list of path and filename parts.  Last part is
   assumed to be a file extension.

   'The name of a resource is a '/'-separated path name that identifies the
   resource.'"
  [path file ext]
  (str (when path (str path "/")) file (when ext (str "." ext))))

(defn- candidate-templates
  "Generate a prioritised list of possible template paths."
  [path tag session]
  (let [[dirpath base ext] (path-components path)
        variants (fn [specifier]
                   (let [p (pathname
                            dirpath
                            (if specifier (str base "_" specifier) base)
                            ext)]
                     [p (str "resources/" p)]))]
    (concat
     (variants tag)
     (variants (name (or (session/os-family session) "unknown")))
     (variants (name (or (session/packager session) "unknown")))
     (variants nil))))

(defn find-template
  "Find a template for the specified path, for application to the given node.
   Templates may be specialised."
  [path session]
  {:pre [(map? session) (session :server)]}
  (some
   get-resource
   (candidate-templates
    path (-> session :server :group-name) session)))

(defn interpolate-template
  "Interpolate the given template."
  [path values session]
  (strint/<<!
   (utils/load-resource-url
    (find-template path session))
   (utils/map-with-keys-as-symbols values)))

;;; programatic templates - umm not really templates at all

(defmacro deftemplate [template [& args] m]
  `(defn ~template [~@args]
     ~m))

(defn- apply-template-file [[file-spec content]]
  (logging/trace (str "apply-template-file " file-spec \newline content))
  (let [path (:path file-spec)]
    (string/join
     ""
     (filter (complement nil?)
             [(stevedore/script
               (var file ~path)
               ((~lib/cat "") > @file <<EOF))
              content
              "\nEOF\n"
              (when-let [mode (:mode file-spec)]
                (stevedore/script (do (chmod ~mode @file))))
              (when-let [group (:group file-spec)]
                (stevedore/script (do (chgrp ~group @file))))
              (when-let [owner (:owner file-spec)]
                (stevedore/script (do (chown ~owner @file))))]))))

;; TODO - add chmod, owner, group
(defn apply-templates [template-fn args]
  (string/join "" (map apply-template-file (apply template-fn args))))
