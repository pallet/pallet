# pallet

Pallet is used to provision configured compute nodes using crane, jclouds and chef.

It uses a declaritive map for specifying the number of nodes with a given tag.
Each tag is used to look up a machine image template specification (in crane and
jsclouds), and to lookup configuration information (in chef).  The converge
function then tries to bring you compute servers into alignment with your
declared counts and configurations.

The bootstrap process for new compute nodes installs a user with sudo
permissions, using the specified username and password. The installed user is
used to execute the chef cookbooks.

Once the nodes are bootstrapped, and fall all existing nodes,
the configured node information is written to the "compute-nodes" cookbook
before chef is run, and this provides a :compute_nodes attribute.  The
compute-nodes cookbook is expected to exist in the site-cookbooks of the
chef-repository you specify with `with-chef-repository`.

`chef-solo` is then run with chef repository you have specified using the node
tag as a configuration target.

## Usage

    (require 'pallet)
    (def cloudservers-user \"your user\")
    (def cloudservers-password \"your api key\")
    (def cloudservers-compute-name cloudservers)

    ;; create a session by verifying credentials
    (def cs (crane.compute/compute-context
              cloudservers-compute-name cloudservers-user cloudservers-password
              (crane.compute/modules :log4j :ssh :enterprise)))

    ;; Describe a user to be used for admin on the machine.
    ;; make-user uses current user's id_rsa key by default.
    (def user (pallet/make-user \"admin-user\" \"admin-password\"))

    ;; Template for describing the node image to be used
    (defn server-template
      [compute public-key-path init-script]
        (.. compute (getComputeService) (templateBuilder)
	(osFamily OsFamily/UBUNTU)
        (osDescriptionMatches "[^J]+9.10[^64]+")
	smallest
        (options (.. (org.jclouds.compute.options.TemplateOptions$Builder/authorizePublicKey (slurp public-key-path))
			   (runScript init-script)))))

    ;; map from tags to templates
    (def templates { :combined server-template :monitor server-template})

    ;; declare the nodes required
    (def required-nodes { :combined 2 :monitor 1})

    ;; create an provision the nodes
    (pallet/with-chef-repository \"path_to_your_chef_repository\"
      (pallet/with-node-templates templates
        (pallet/converge cs required-nodes user)))

## Todo

Make password handling shell character safe.
Add error handling.
Make the template declarations nicer.

## Installation

Installation is with leiningen.  Add `[pallet "0.0.1-SNAPSHOT"]` to your :dependencies in project.clj.
