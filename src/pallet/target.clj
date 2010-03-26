(ns pallet.target
  "Provide information about the target image")

;; A conscious decision was made to use rebindable vars here, as passing them around
;; explicitly would create a lot of noise in resources, templates and crates
(def *target-template* nil)
(def *target-tag* nil)

(defmacro with-target-template [template & body]
  `(binding [*target-template* ~template]
    ~@body))

(defmacro with-target-tag [tag & body]
  `(binding [*target-tag* ~tag]
    ~@body))

(defn os
  "OS family"
  ([] (os *target-template*))
  ([target] (:os-family target)))

(defn admin-group
  "Default administrator group"
  ([] (admin-group *target-template*))
  ([target]
     (condp = (os target)
       :ubuntu "adm"
       "wheel")))

(defn packager
  "Default package manager"
  ([] (packager *target-template*))
  ([target]
     (cond
      (some #(#{:ubuntu :debian :jeos} %) target)
      :aptitude
      (some #(#{:centos :rhel} %) target)
      :yum
      (some #(#{:gentoo} %) target)
      :portage)))

