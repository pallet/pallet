(ns #^{:author "Hugo Duncan"}
  demo
  "A demo for pallet + jclouds.

  ;; First we load the demo package, and switch to the demo namespace
  (require 'demo)
  (in-ns 'demo)

  ;; Supported providers can be found with
  (supported-clouds)

  ;; We provide some credentials
  (def my-user \"your user\")
  (def my-password \"your api key\")

  ;; and log in to the cloud using the credentials defined above.
  ;; provider is a string specifiying the provider, as returned
  ;; from (supported-clouds)
  (def context (compute-context \"provider\" my-user my-password))

  ;; nodes can be listed with the nodes function
  (nodes context)

  ;; We can create a node, by specifying a name tag and a template.
  ;; webserver-template is a vector specifying features we want in
  ;; our image.
  (start-node context :webserver webserver-template)

  ;; At this point we can manage instance counts as a map.
  ;; e.g ensure that we have two webserver nodes
  (with-node-templates templates
    (converge context {:webserver 2}))

  ;; ... and we can remove our nodes
  (with-node-templates templates
    (converge context {:webserver 0}))

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
    (converge context {:webserver 1}
      (bootstrap-with (bootstrap-template :ensure-resolve)
                      (bootstrap-template :update-pkg-mgr))))

  ;; We probably want an admin user.  We can ensure our admin
  ;; user is created with the image by specifying :boostrap-admin-user.
  (def user (make-user \"admin-user\" :password \"admin-password\"))

  ;; this time we create the a bootstrapped node
  (with-node-templates templates
    (converge context {:webserver 1}
      (bootstrap-with (bootstrap-template :ensure-resolve)
                      (bootstrap-admin-user user))))

  ;; Bootstrapping is fine, but we might also want to configure the machines
  ;; with chef
  (with-node-templates templates
    (converge context {:webserver 1}
      (bootstrap-with (bootstrap-template :ensure-resolve)
                      (bootstrap-template :update-pkg-mgr)
                      (bootstrap-template :install-rubygems)
                      (bootstrap-template :install-chef)
                      (bootstrap-admin-user user))
      (configure-with-chef user \"path_to_your_chef_repository\")))

  ;; and we can then run chef-solo at any time with
  (cook-nodes (nodes context) user \"path_to_your_chef_repository\")"
(:use org.jclouds.compute
        pallet.utils
        pallet.core
        pallet.chef
        pallet.resource
        pallet.resource-apply
        pallet.compute
        pallet.crate.automated-admin-user
        pallet.bootstrap
        clj-ssh.ssh))


(def webserver-template [:ubuntu :X86_64 :smallest :os-description-matches "[^J]+9.10[^32]+"])
(def balancer-template (apply vector :inbound-ports [22 80] webserver-template))

(def #^{ :doc "This is a map defining node tag to instance template builder."}
     templates { :webserver webserver-template :balancer balancer-template })

(def #^{ :doc "This is a map defining node tag to number of instances."}
     the-farm
     { :webserver 2 :balancer 1 })

