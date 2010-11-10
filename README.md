![pallet logo](http://github.com/downloads/hugoduncan/pallet/pallet-logo.png)

Pallet is used to provision and maintain compute nodes, and aims to solve the
problem of providing a consistently configured running image across a range of
clouds.  It is designed for use from the [Clojure](http://clojure.org) REPL, from
clojure code, and from the command line.

It uses [jclouds](http://github.com/jclouds/jclouds) to gain portable access to
different cloud providers.  While jclouds solves the issue of creating,
destroying and configuring cloud level access to nodes, it does not address the
differences in images used across providers.  This is what Pallet adds.

`defnode` is used to decalre node types, specifying the image template and
possibly bootstrap and other configuration. The `converge` function can then be
used to control the number of nodes of each type that are running in your cloud,
and applies the declared configuration as required.  The `lift` function can
also be used to apply configuration without adjusting node counts.  Both
converge and lift accept inline definiton of configuration actions that should
be run.

In pallet, low level resources can be combined in clojure functions, known as
crates, that are used to specify configuration.  Crates are clojure functions
that have an initial `request` argument, and can call other crates, with
arguments, as required. The request argument is used to carry the configuration
request state, and is updated, and returned by each reasource function.  The
request map must be threaded through each resource or crate call.

Crates can be packaged and distributed as clojure jar files.

Some basic [documentation](http://hugoduncan.github.com/pallet) is available.

## Usage

[See demo documentation](http://hugoduncan.github.com/pallet/autodoc/demo-api.html).

There is an introductory [screencast](http://www.youtube.com/hugoduncan),
showing a basic node configuration, and starting and stopping a node.

## Support

[On the group](http://groups.google.com/group/pallet-clj), or #pallet on freenode irc.

## Installation

Pallet is distributed as a jar, and is available in the [clojars repository](http://clojars.org/org.cloudhoist/pallet).

Installation is with [Leiningen](http://github.com/technomancy/leiningen),
maven, or your favourite maven repository aware build tool.

### Quickstart

If you just want to try out pallet, then you can follow these instructions:

- Download [the tarfile](http://github.com/hugoduncan/pallet/tarball/master)
  or [zipfile](http://github.com/hugoduncan/pallet/zipball/master), and unpack.

- Install [Leiningen](http://github.com/technomancy/leiningen).

- In a shell, go to the directory containing the pallet source code and enter

        $ lein deps
        $ lein repl

You should now have a working repl, which you can use to explore pallet.  You
might want to make the basic pallet commands available without namespace prefix
by typing the following at the repl.

        user> (use 'pallet.repl)
	user> (use-pallet)


## See also
[jclouds](http://github.com/jclouds/jclouds),
[chef](http://wiki.opscode.com/display/chef/Home),
[crane](http://github.com/bradford/crane)

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

[Contributors](https://www.ohloh.net/p/pallet-clj/contributors)

Copyright 2010 Hugo Duncan.
