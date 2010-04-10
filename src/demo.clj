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
  (def service (compute-service \"provider\" my-user my-password :log4j))

  ;; nodes can be listed with the nodes function
  (nodes service)

  ;; the compute service can also be bound
  (with-compute-service [service]
    (nodes))

  ;; or even
  (with-compute-service [\"provider\" my-user my-password :log4j]
    (nodes))

  ;; In order to create nodes, we need to define node types.
  ;; :image is a vector specifying features we want in our image.
  (defnode webserver
    :image [:ubuntu :X86_64 :smallest
            :os-description-matches \"[^J]+9.10[^32]+\"])

  ;; We can create a node, by specifying the node type.
  (start-node webserver service)

  ;; At this point we can manage instance counts as a map.
  ;; e.g ensure that we have two webserver nodes
  (converge {webserver 2} service)

  ;; ... and we can remove our nodes
  (converge {webserver 0} service)

  ;; Images are configured differently between clouds and os's.
  ;; Pallet comes with some \"crates\" that can be used to normalise
  ;; the images.  public-dns-if-no-nameserver ensures there is a
  ;; nameserver if none is already configured.
  ;; We probably want an admin user. The default for automated-admin-user is to
  ;; use your current login name, with no password and your id_rsa ssh key.
  (defnode webserver
    :image [:ubuntu :X86_64 :smallest
            :os-description-matches \"[^J]+9.10[^32]+\"]
    :bootstrap [(public-dns-if-no-nameserver)
                (automated-admin-user)])

  ;; recreate a node with our admin-user
  (converge {webserver 1} service)

  ;; Another example, that adds java to our node type, then converges
  ;; to install java
  (defnode webserver
    :image [:ubuntu :X86_64 :smallest
            :os-description-matches \"[^J]+9.10[^32]+\"]
    :bootstrap [(public-dns-if-no-nameserver)
                (automated-admin-user)]
    :configure [(java :openjdk)])

  (converge {webserver 1} service)

  ;; if you do not want to adjust the number of nodes, there is also
  ;; a lift function, which can be used to apply configuration
  (lift webserver service)

  ;; :bootstrap and :configure are two phases.  These are used by default
  ;; by the converge method.  The :bootstrap phase applies to nodes on first
  ;; boot, and :configure on every invocation of converge. You can also
  ;; specify other phases, either as a keyword, in which case the
  ;; configuration is taken from the corresponding section of the
  ;; defnode, or as an inline definiton, with phase
  (converge {webserver 1} service :remove-build-tools)
  (converge {webserver 1} service (phase (package \"curl\")))

  ;; :configure is also the default phase for lift, but unlike with
  ;; converge, the :configure phase is not added if not specified.
  (lift [webserver balancer] service (phase (package \"curl\")))

  ;; you can manage arbitrary machines that are ssh accesable, including
  ;; local virtual machines.  For this to work you may have to specify
  ;; the :sudo-password option in the admin user, even if you can
  ;; log in without a password
  (defnode vm :image [:ubuntu])
  (def vm1 (make-unmanaged-node \"vm\" \"localhost\" :ssh-port 2223))
  (with-admin-user [\"myuser\" :sudo-password \"xxx\"]
    (lift vm1 service (phase (package \"curl\"))))

  ;; We might also want to configure the machines with chef-solo.
  ;; This expects a webserver.json file in the cookbook repository's
  ;; config directory.
  (lift webserver service (phase (chef)))
  (cook webserver \"path_to_your_chef_repository\")"
(:use [org.jclouds.compute :exclude [node-tag]]
      pallet.utils
      pallet.core
      pallet.chef
      pallet.resource
      pallet.resource.package
      pallet.compute
      pallet.crate.automated-admin-user
      pallet.crate.public-dns-if-no-nameserver
      pallet.bootstrap
      pallet.crate.rubygems
      pallet.crate.ruby
      pallet.crate.java
      pallet.crate.chef
      clj-ssh.ssh))

(defnode webserver
  :image [:ubuntu :X86_64 :smallest :os-description-matches "[^J]+9.10[^32]+"]
  :bootstrap [(automated-admin-user)]
  :configure [(package "apache2")])

(defnode balancer
  :image (apply vector :inbound-ports [22 80] (webserver :image))
  :bootstrap [(automated-admin-user)])

(defnode centos
  :image [:centos :X86_64 :smallest :os-description-matches ".*5.3.*"
          :image-description-matches "[^gr]+"]
  :bootstrap [(automated-admin-user)])


