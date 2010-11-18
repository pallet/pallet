![pallet logo](https://github.com/downloads/hugoduncan/pallet/pallet-logo.png)

[Pallet](http://palletops.com) is used to provision and maintain compute nodes,
and aims to solve the problem of providing a consistently configured running
image across a range of clouds.  It is designed for use from the
[Clojure](http://clojure.org) REPL, from clojure code, and from the command
line.

It uses [jclouds](https://github.com/jclouds/jclouds) to gain portable access to
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

There is an introductory [screencast](http://www.youtube.com/hugoduncan),
showing a basic node configuration, and starting and stopping a node.

## Support

[On the group](http://groups.google.com/group/pallet-clj), or #pallet on freenode irc.

## Quickstart

See the [basic usage](https://github.com/hugoduncan/pallet-examples/tree/master/basic/)
example in the [pallet-examples](https://github.com/hugoduncan/pallet-examples)
project.

## Installation

Pallet is distributed as a jar, and is available in the [sonatype repository](http://oss.sonatype.org/content/repositories/releases/org/cloudhoist).

Installation is with maven or your favourite maven repository aware build tool.

### lein/cake project.clj

    :dependencies [[org.cloudhoist/pallet "0.4.0-SNAPSHOT"]
                   [org.cloudhoist/pallet-crates-complete "0.4.0-SNAPSHOT"]]
    :repositories {"sonatype"
                   "http://oss.sonatype.org/content/repositories/releases"
                   "sonatype-snapshots"
                   "http://oss.sonatype.org/content/repositories/snapshots"}

### maven pom.xml

    <dependencies>
      <dependency>
        <groupId>org.cloudhoist</groupId>
        <artifactId>pallet</artifactId>
        <version>0.4.0-SNAPSHOT</version>
      </dependency>
      <dependency>
        <groupId>org.cloudhoist</groupId>
        <artifactId>pallet</artifactId>
        <version>0.4.0-SNAPSHOT</version>
      </dependency>
    <dependencies>

    <repositories>
      <repository>
        <id>sonatype</id>
        <url>http://oss.sonatype.org/content/repositories/releases</url>
      </repository>
      <repository>
        <id>sonatype-snapshots</id>
        <url>http://oss.sonatype.org/content/repositories/snapshots</url>
      </repository>
    </repositories>



## See also
[jclouds](https://github.com/jclouds/jclouds),
[chef](http://wiki.opscode.com/display/chef/Home),
[crane](https://github.com/bradford/crane)

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

[Contributors](https://www.ohloh.net/p/pallet-clj/contributors)

Copyright 2010 Hugo Duncan.
