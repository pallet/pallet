![pallet logo](https://github.com/downloads/pallet/pallet/pallet-logo.png)

[![Build Status](https://travis-ci.org/rstradling/pallet.png?branch=support/0.7.x)](undefined)

[Pallet](http://palletops.com) is used to provision and maintain servers on
cloud and virtual machine infrastructure, and aims to solve the problem of
providing a consistently configured running image across a range of clouds.  It
is designed for use from the [Clojure](http://clojure.org) REPL, from clojure
code, and from the command line.

- reuse configuration in development, testing and production.
- store all your configuration in a source code management system (eg. git),
  including role assignments.
- configuration is re-used by compostion; just create new functions that call
  existing crates with new arguments. No copy and modify required.
- enable use of configuration crates (recipes) from versioned jar files.

It uses [jclouds](https://github.com/jclouds/jclouds) to gain portable access to
different cloud providers.

Some basic [documentation](http://pallet.github.com/pallet) is available.

## Crates

Pallet installation and configuration for various software packages is provided
via crates.  For pallet 0.4.x through 0.6.x, the crates are in the
[pallet-crates](https://github.com/pallet/pallet-crates) repo. For 0.7.x and
on, each crate is in its own repository.

## Support

On the [mailing list](http://groups.google.com/group/pallet-clj), or #pallet on
freenode irc.

## Usage

There is an introductory [screencast](http://www.youtube.com/hugoduncan#p/u/1/adzMkR0d0Uk),
showing a basic node configuration, and starting and stopping a node.

## Quickstart

See the
[basic usage](https://github.com/pallet/pallet-examples/blob/master/basic/src/demo.clj)
example in the
[pallet-examples basic](https://github.com/pallet/pallet-examples/tree/master/basic/)
project.

For general help getting started with Clojure, see this
[guide](http://www.assembla.com/wiki/show/clojure/Getting_Started).

## Installation

Pallet is distributed as a jar, and is available in the
[sonatype repository](http://oss.sonatype.org/content/repositories/releases/org/cloudhoist).

Installation is with maven or your favourite maven repository aware build tool.

See also the [first steps](http://palletops.com/doc/first-steps) documentation
for instructions on how to create a project with a `lein new` template.

### lein/cake project.clj

    :dependencies [[org.cloudhoist/pallet "0.7.2"]]
    :repositories {"sonatype"
                   "http://oss.sonatype.org/content/repositories/releases"
                   "sonatype-snapshots"
                   "http://oss.sonatype.org/content/repositories/snapshots"}

### maven pom.xml

    <dependencies>
      <dependency>
        <groupId>org.cloudhoist</groupId>
        <artifactId>pallet</artifactId>
        <version>0.7.2</version>
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
[jclouds](http://jclouds.org/),
[chef](http://opscode.com/),
[puppet](http://www.puppetlabs.com/),
[crane](https://github.com/clj-sys/crane)

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

[Contributors](https://www.ohloh.net/p/pallet-clj/contributors)

Copyright 2010 Hugo Duncan.
