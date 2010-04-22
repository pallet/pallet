(ns pallet.target
  "Provide information about the target image")

;; A conscious decision was made to use rebindable vars here, as passing them around
;; explicitly would create a lot of noise in resources, templates and crates
(def *target-node* nil)
(def *target-node-type* nil)

(defmacro with-target
  [node node-type & body]
  `(binding [*target-node* ~node
             *target-node-type* ~node-type]
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

(defn os
  "OS family"
  ([] (os (template)))
  ([target] (:os-family target)))

(defn admin-group
  "Default administrator group"
  ([] (admin-group (template)))
  ([target]
     (condp = (os target)
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

