(ns #^{:doc "
A demo for pallet + crane + jclouds.

  ;; First we provide some credentials
  (def terremark-user \"your user\")
  (def terremark-password \"your api key\")

  ;; Npw we can load the demo package
  (require 'demo)
  (in-ns 'demo)

  ;; and log in to the cloud using the credentials defined above.
  (def tm (demo/terremark))

  ;; At this point we can manage instance counts as a map.
  ;; e.g add a node using the webserver template
  (with-node-templates templates
    (converge tm {:webserver 1}))

  ;; ... and remove it again
  (with-node-templates templates
    (converge tm {:webserver 0}))

  ;; templates is a description of the images to use for each of
  ;; our node tags
  templates

  ;; Images are configured differently between clouds and os's.
  ;; We might want to update our machines to use the latest
  ;; package manager.  pallet has a couple of templates, and
  ;; you can add your own, see resources/bootstrap.  Templates
  ;; have a default implementation, but can be specialised
  ;; for a given tag or operating system family.
  (with-node-templates templates
    (converge tm {:webserver 1}
      (bootstrap-with (bootstrap-template :ensure-resolve)
                      (bootstrap-template :update-pkg-mgr))))

  ;; We probably want an admin user.  We can ensure our admin
  ;; user is created with the image by specifying :boostrap-admin-user.
  (def user (make-user \"admin-user\" \"admin-password\"))

  ;; this time we create the a bootstrapped node
  (with-node-templates templates
    (converge tm {:webserver 1}
      (bootstrap-with (bootstrap-template :ensure-resolve)
                      (bootstrap-admin-user user))))

  ;; Bootstrapping is fine, but we might also want to configure the machines
  ;; with chef
  (with-node-templates templates
    (converge tm {:webserver 1}
      (bootstrap-with (bootstrap-template :ensure-resolve)
                      (bootstrap-template :update-pkg-mgr)
                      (bootstrap-admin-user user))
      (configure-with-chef user \"path_to_your_chef_repository\")))

  ;; and we can tun chef-solo at any time with
  (cook-nodes (nodes tm) user \"path_to_your_chef_repository\")

" :author "Hugo Duncan"}
  demo
  (:use crane.compute
        pallet.utils
        pallet.core
        pallet.chef
        pallet.resource
        pallet.crate.automated-admin-user
        pallet.bootstrap))

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

(def webserver-template [:ubuntu :X86_64 :smallest :os-description-matches "[^J]+9.10[^32]+"])
(def balancer-template (apply vector :inbound-ports [22 80] webserver-template))

(def #^{ :doc "This is a map defining node tag to instance template builder."}
     templates { :webserver webserver-template :balancer balancer-template })

(def #^{ :doc "This is a map defining node tag to number of instances."}
     the-farm
     { :webserver 2 :balancer 1 })

