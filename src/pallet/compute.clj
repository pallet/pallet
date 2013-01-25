(ns pallet.compute
  "Abstraction of the compute interface"
  (:require
   [pallet.common.deprecate :as deprecate]
   [pallet.compute.implementation :as implementation]
   [pallet.utils :as utils]))

;;; Meta
(defn supported-providers
  "A list of supported provider names.  Each name is suitable to be passed
   to compute-service."
  []
  (implementation/supported-providers))

;;; Compute Service instantiation
(defn compute-service
  "Instantiate a compute service. The provider name should be a recognised
   jclouds provider, or \"node-list\". The other arguments are keyword value
   pairs.
   - :identity     username or key
   - :credential   password or secret
   - :extensions   extension modules for jclouds
   - :node-list    a list of nodes for the \"node-list\" provider.
   - :environment  an environment map with service specific values."
  [provider-name
   & {:keys [identity credential extensions node-list endpoint environment sub-services]
      :as options}]
  (implementation/load-providers)
  (implementation/service provider-name options))

;;; Actions

;; TODO
;; the executor should be passed to the compute service to allow remote
;; execution of the init script using the executor abstraction.

;; However, the executor uses the session abstraction, so that would need
;; passing too
(defprotocol ComputeService
  (nodes [compute] "List nodes")
  (run-nodes
    [compute group-spec node-count user init-script options]
    "Start node-count nodes for group-spec, executing an init-script
     on each, using the specified user and options.")
  (reboot [compute nodes]
    "Reboot the specified nodes")
  (boot-if-down
   [compute nodes]
   "Boot the specified nodes, if they are not running.")
  (shutdown-node [compute node user] "Shutdown a node.")
  (shutdown [compute nodes user] "Shutdown specified nodes")
  (ensure-os-family
   [compute group-spec]
   "Called on startup of a new node to ensure group-spec has an os-family
   attached to it.")
  (destroy-nodes-in-group [compute group-name])
  (destroy-node [compute node])
  (images [compute])
  (close [compute]))

(defprotocol ComputeServiceProperties
  (service-properties [compute]
    "Return a map of service details.  Contains a :provider key at a minimum.
    May contain current credentials."))

(defprotocol NodeTagReader
  "Provides a SPI for tagging nodes with values."
  (node-tag [compute node tag-name] [compute node tag-name default-value]
    "Return the specified tag on the node.")
  (node-tags [compute node]
    "Return the tags on the node."))

(defprotocol NodeTagWriter
  "Provides a SPI for adding tags to nodes."
  (tag-node! [compute node tag-name value]
    "Set a value on the given tag-name on the node.")
  (node-taggable? [compute node]
    "Predicate to test the availability of tags on a node."))


;; (defn nodes-by-tag [nodes]
;;   (reduce #(assoc %1
;;              (keyword (tag %2))
;;              (conj (get %1 (keyword (tag %2)) []) %2)) {} nodes))

;; (defn node-counts-by-tag [nodes]
;;   (reduce #(assoc %1
;;              (keyword (tag %2))
;;              (inc (get %1 (keyword (tag %2)) 0))) {} nodes))

;;; Hierarchies

(def os-hierarchy
  (-> (make-hierarchy)
      (derive :linux :os)

      ;; base distibutions
      (derive :rh-base :linux)
      (derive :debian-base :linux)
      (derive :arch-base :linux)
      (derive :suse-base :linux)
      (derive :bsd-base :linux)
      (derive :gentoo-base :linux)

      ;; distibutions
      (derive :centos :rh-base)
      (derive :rhel :rh-base)
      (derive :amzn-linux :rh-base)
      (derive :fedora :rh-base)

      (derive :debian :debian-base)
      (derive :ubuntu :debian-base)
      (derive :jeos :debian-base)

      (derive :arch :arch-base)
      (derive :gentoo :gentoo-base)
      (derive :darwin :bsd-base)
      (derive :os-x :bsd-base)))

(defmacro defmulti-os
  "Defines a defmulti used to abstract over the target operating system. The
   function dispatches based on the target operating system, that is extracted
   from the session passed as the first argument.

   Version comparisons are not included"
  [name [& args]]
  `(do
     (defmulti ~name
       (fn [~@args] (-> ~(first args) :server :image :os-family))
       :hierarchy #'os-hierarchy)

     (defmethod ~name :default [~@args]
       (throw
        (ex-info
         (format
          "%s does not support %s"
          ~name (-> ~(first args) :server :image :os-family))
         {:type :pallet/unsupported-os})))))

;;; target mapping
(defn packager-for-os
  "Package manager"
  [os-family os-version]
  (cond
    (#{:debian :jeos} os-family) :aptitude
    (#{:ubuntu} os-family) (if (= "11.10" os-version)
                             :apt
                             :aptitude)
    (#{:centos :rhel :amzn-linux :fedora} os-family) :yum
    (#{:arch} os-family) :pacman
    (#{:suse} os-family) :zypper
    (#{:gentoo} os-family) :portage
    (#{:darwin :os-x} os-family) :brew
    :else (throw
           (ex-info
            (format "Unknown packager for %s %s" os-family os-version)
            {:type :unknown-packager}))))

(defn packager
  "Package manager"
  [target]
  (or
   (:packager target)
   (let [os-family (:os-family target)]
     (cond
      (#{:debian :jeos} os-family) :aptitude
      (#{:ubuntu} os-family) (let [version (:os-version target)]
                               (if (= "11.10" version)
                                 :apt
                                 :aptitude))
      (#{:centos :rhel :amzn-linux :fedora} os-family) :yum
      (#{:arch} os-family) :pacman
      (#{:suse} os-family) :zypper
      (#{:gentoo} os-family) :portage
      (#{:darwin :os-x} os-family) :brew
      :else (throw
             (ex-info
              (format
               "Unknown packager for %s - :image %s"
               os-family target)
              {:type :unknown-packager}))))))

(defn base-distribution
  "Base distribution for the target."
  [target]
  (or
   (:base-distribution target)
   (let [os-family (:os-family target)]
     (cond
      (#{:ubuntu :debian :jeos} os-family) :debian
      (#{:centos :rhel :amzn-linux :fedora} os-family) :rh
      (#{:arch} os-family) :arch
      (#{:suse} os-family) :suse
      (#{:gentoo} os-family) :gentoo
      (#{:darwin :os-x} os-family) :os-x
      :else (throw
             (ex-info
              (format
               "Unknown base-distribution for %s - target is %s"
               os-family target)
              {:type :unknown-packager}))))))

(defn admin-group
  "User that remote commands are run under"
  ([target]
     (admin-group (-> target :image :os-family) nil))
  ([os-family os-version]
     (case os-family
       :centos "wheel"
       :rhel "wheel"
       "adm")))
