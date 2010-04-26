(ns #^{:author "Hugo Duncan"
       :see-also [["chef" "http://wiki.opscode.com/display/chef/Home"]
                  ["jclouds" "http://github.com/jclouds/jclouds"]]}
  pallet
"Pallet is used to provision and maintain compute nodes, and aims to solve the
problem of providing a consistently configured running image across a range of
clouds.  It is designed for use from the Clojure (http://clojure.org) REPL, and
from clojure scripts.

It uses jclouds (http://github.com/jclouds/jclouds) to gain portable access to
different cloud providers.  While jclouds solves the issue of creating
destroying and configuring cloud level access to nodes, it does not address the
differences in images used across providers.  This is what Pallet adds.

Pallet can be used in a declaritive or an imperative fashion. defnode is used to
decalre node types, specifying the image template and possibly bootstrap and
other configuration. The converge function can then be used to control the
number of nodes of each type that are running in your cloud, and applies the
declared configuration as required.  The lift function can also be used to apply
configuration without adjusting node counts.  Both converge and lift both accept
inline definiton of configuration actions that should be run.

In pallet, low level resources can be combined in clojure functions, known as
crates, that are used to specify configuration.  Crates are clojure functions
and can call other crates, with arguments, as required. Crates can be packaged
and distributed as clojure jar files.

Pallet can also execute chef cookbooks using 'chef-solo'.

API documentation (http://hugoduncan.github.com/pallet) is available.

## Usage

See demo documentation (http://hugoduncan.github.com/pallet/demo-api.html).

## Todo

Make password handling shell character safe.
Add error handling.
Add progress reporting.
Add templating by cloud provider.

## Installation

Installation is with Leiningen (http://github.com/technomancy/leiningen).  Add
`[pallet \"0.0.1-SNAPSHOT\"]` to your :dependencies in project.clj.

"
  (:require pallet.core pallet.utils pallet.compute pallet.bootstrap pallet.chef))

(refer 'pallet.core 'pallet.utils 'pallet.compute 'pallet.bootstrap 'pallet.chef)
