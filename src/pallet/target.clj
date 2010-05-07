(ns pallet.target
  "Provide information about the target image"
  (:require
   [org.jclouds.compute :as jclouds]
   [pallet.compute :as compute])
  (:use
   [clojure.contrib.def :only [defunbound]]))

;; A conscious decision was made to use rebindable vars here, as passing them around
;; explicitly would create a lot of noise in resources, templates and crates
(defunbound *all-nodes* "All nodes in service.")
(defunbound *target-nodes* "All nodes targeted by the current operation.")
(defunbound *target-node* "Current node.")
(defunbound *target-node-type* "Node-type of current node.")

(defmacro with-target
  [node node-type & body]
  `(binding [*target-node* ~node
             *target-node-type* ~node-type]
    ~@body))

(def jvm-os-map
     { "Mac OS X" :os-x })

(defmacro with-local-target
  [& body]
  `(with-target
     (compute/make-node "localhost")
     {:image [(get jvm-os-map (System/getProperty "os.name"))]}
     ~@body))

(defmacro with-nodes
  [nodes target-nodes & body]
  `(binding [*all-nodes* ~nodes
             *target-nodes* ~target-nodes]
    ~@body))

(defn tag
  ([] (tag *target-node-type*))
  ([node-type] (:tag node-type)))

(defn template
  ([] (template *target-node-type*))
  ([node-type] (:image node-type)))

(defn node
  [] *target-node*)

(defn node-type
  [] *target-node-type*)

(defn all-nodes
  [] *all-nodes*)

(defn target-nodes
  [] *target-nodes*)

(defn os-family
  "OS family"
  ([] (os-family (template)))
  ([target] (some (set (map (comp keyword str) (jclouds/os-families))) target)))

(defn admin-group
  "Default administrator group"
  ([] (admin-group (template)))
  ([target]
     (condp = (os-family target)
       :ubuntu "adm"
       "wheel")))

(defn packager
  "Default package manager"
  ([] (packager (template)))
  ([target]
     (cond
      (some #(#{:ubuntu :debian :jeos} %) target)
      :aptitude
      (some #(#{:centos :rhel} %) target)
      :yum
      (some #(#{:gentoo} %) target)
      :portage
      :else
      :aptitude)))

