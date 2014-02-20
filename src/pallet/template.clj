(ns pallet.template
  "Template file writing"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.actions :refer [remote-file]]
   [pallet.session :refer [group-name os-family packager]]
   [pallet.strint :as strint]
   [pallet.utils :as utils]
   [pallet.utils :refer [apply-map]]))

(defn ^java.net.URL get-resource
  "Loads a resource. Returns a URI."
  [path]
  (-> (clojure.lang.RT/baseLoader) (.getResource path)))

(defn path-components
  "Split a resource path into path, basename and extension components."
  [^String path]
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
  [path group-name session]
  (let [[dirpath base ext] (path-components path)
        variants (fn [specifier]
                   (let [p (pathname
                            dirpath
                            (if specifier (str base "_" specifier) base)
                            ext)]
                     [p (str "resources/" p)]))]
    (concat
     (variants group-name)
     (variants (name (or (os-family session) "unknown")))
     (variants (name (or (packager session) "unknown")))
     (variants nil))))

(defn find-template
  "Find a template for the specified path, for application to the given node.
   Templates may be specialised."
  [path session]
  {:pre [(map? session) (session :server)]}
  (some
   get-resource
   (candidate-templates path (group-name session) session)))

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

(defn- apply-template-file
  [[file-spec content]]
  (logging/trace (str "apply-template-file " file-spec \newline content))
  (apply-map remote-file (:path file-spec) :content content
             (dissoc file-spec :path)))

(defn apply-templates
  [template-fn args]
  (doseq [f (apply template-fn args)]
    (apply-template-file f)))
