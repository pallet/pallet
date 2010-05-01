# Pallet

Pallet is used to provision configured compute nodes, and aims to solve the
problem of providing a consistently configured running image across a range of
clouds.  It is designed for use from the [Clojure](http://clojure.org) REPL, and
from clojure scripts.

It uses [jclouds](http://github.com/jclouds/jclouds) to gain portable access to
different cloud providers.  While jclouds solves the issue of creating,
destroying and configuring cloud level access to nodes, it does not address the
differences in images used across providers.  This is what Pallet adds.

Pallet works with a declaritive map for specifying the number of nodes with a
given tag.  Each tag is used to look up a machine image template specification
(in jclouds), and to lookup configuration information.  The converge function
then tries to bring you compute servers into alignment with your declared counts
and configurations.

The bootstrap process for new compute nodes is configurable.  At the moment
there is the possibility of using scripts, which are specialised by tag or
operating system family, and of using installing an admin user with sudo
permissions, using the specified username and password.  Script templates exist
for ensuring a functional resolv configuration, package manager update,
installation of rubygems, etc, and can be added to freely.

An installed admin user can be used to execute chef cookbooks using 'chef-solo'.
Once the nodes are bootstrapped, and fall all existing nodes, the configured
node information is written to the "compute-nodes" cookbook before chef is run,
and this provides a :compute_nodes attribute.  The compute-nodes cookbook is
expected to exist in the site-cookbooks of the chef-repository you specify with
`with-chef-repository`. `chef-solo` is then run with chef repository you have
specified using the node tag as a configuration target.

This can also be used to bootstrap chef-server and chef client installation.

[API documentation](http://hugoduncan.github.com/pallet) is available.

## Usage

[See demo documentation](http://hugoduncan.github.com/pallet/demo-api.html).

## Todo

Make password handling shell character safe.
Add error handling.
Add progress reporting.
Add templating by cloud provider.

## Installation

Installation is with [Leiningen](http://github.com/technomancy/leiningen).  Add
`[pallet "0.0.1-SNAPSHOT"]` to your :dependencies in project.clj.

### Quickstart

If you just want to try out pallet, then you can follow these instructions:

- Download [the tarfile](http://github.com/hugoduncan/pallet/tarball/master)
  or [zipfile](http://github.com/hugoduncan/pallet/zipball/master), and unpack.

- Install [Leiningen](http://github.com/technomancy/leiningen).

- In a shell, go to the directory containing the pallet source code and enter

        $ lein deps
        $ lein compile-java
        $ lein repl

You should now have a working repl, which you can use to explore pallet.


## See also
[chef](http://wiki.opscode.com/display/chef/Home),
[crane](http://github.com/bradford/crane),
[jclouds](http://github.com/jclouds/jclouds)

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)
