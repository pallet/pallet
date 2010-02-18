(ns #^{:doc "
A demo for pallet + crane + jclouds.

  (def cloudservers-user \"your user\")
  (def cloudservers-password \"your api key\")
  (def cs (demo/cloudservers))

  (def user (pallet/make-user \"admin-user\" \"admin-password\"))

  (pallet/with-chef-repository \"path_to_your_chef_repository\"
    (pallet/with-node-templates demo/templates
      (pallet/converge cs demo/the-farm user)))

"}
  demo
  (:use crane.compute pallet)
   (:import org.jclouds.compute.domain.OsFamily
	    org.jclouds.compute.options.TemplateOptions
	    org.jclouds.compute.domain.NodeMetadata))

;;; Terremark
(def terremark-compute-name "terremark")
(defn terremark
  "Return a terremark compute context."
  []
  (crane.compute/compute-context
   terremark-compute-name user/terremark-user user/terremark-password
   (crane.compute/modules :log4j :ssh :enterprise)))

;;; Cloudservers
(def cloudservers-compute-name "cloudservers")
(defn cloudservers
  "Return a cloudservers compute context."
  []
  (crane.compute/compute-context
   cloudservers-compute-name user/cloudservers-user user/cloudservers-password
   (crane.compute/modules :log4j :ssh :enterprise)))

(defn server-template
  "Cheapest Ubuntu 9.10 Server"
  [#^org.jclouds.compute.ComputeServiceContext compute public-key-path init-script]
  (.. compute (getComputeService) (templateBuilder)
      (osFamily OsFamily/UBUNTU)
      (osDescriptionMatches "[^J]+9.10[^64]+")
      smallest
      (options (.. (org.jclouds.compute.options.TemplateOptions$Builder/authorizePublicKey (slurp public-key-path))
		   (runScript init-script)))))

(def #^{ :doc "This is a map defining node tag to instance template builder."}
     templates { :combined server-template})

(def #^{ :doc "This is a map defining node tag to number of instances."}
     the-farm
     { :combined 1 })

