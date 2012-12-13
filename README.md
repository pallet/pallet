This is the unstable development branch. See the
[stable branch](https://github.com/pallet/pallet/tree/support/0.7.x) for
production code.

[Pallet][palletops] is used to provision and maintain servers on cloud and
virtual machine infrastructure, and aims to solve the problem of providing a
consistently configured running image across a range of clouds.  It is designed
for use from the [Clojure][clojure] REPL, from clojure code, and from the
command line.

- reuse configuration in development, testing and production.
- store all your configuration in a source code management system (eg. git),
  including role assignments.
- configuration is re-used by compostion; just create new functions that call
  existing crates with new arguments. No copy and modify required.
- enable use of configuration crates (recipes) from versioned jar files.

It uses [jclouds][jclouds] to gain portable access to different cloud providers.

[Documentation][docs] is available.

## Support

On the [mailing list][ml], or #pallet on freenode irc.

## Usage

The main [documentation][docs] is on the [PalletOps][palletops] site.

## Installation

Pallet is distributed as a jar, and is available in the
[sonatype repository][sonatype].

Installation is with maven or your favourite maven repository aware build tool.

### lein project.clj

```clojure
:dependencies [[org.cloudhoist/pallet "0.8.0-alpha.6"]]
:repositories {"sonatype"
               "http://oss.sonatype.org/content/repositories/releases"}
```

### maven pom.xml

```xml
<dependencies>
  <dependency>
    <groupId>org.cloudhoist</groupId>
    <artifactId>pallet</artifactId>
    <version>0.8.0-alpha.6</version>
  </dependency>
<dependencies>

<repositories>
  <repository>
    <id>sonatype</id>
    <url>http://oss.sonatype.org/content/repositories/releases</url>
  </repository>
</repositories>
```

## See also

[chef][chef], and [puppet][puppet] are other infrastructure automation tools.

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

[Contributors](https://www.ohloh.net/p/pallet-clj/contributors)

Copyright 2010, 2011, 2012  Hugo Duncan.


[palletops]: http://palletops.com "Pallet site"

[docs]: http://palletops.com/doc "Pallet Documentation"
[ml]: http://groups.google.com/group/pallet-clj "Pallet mailing list"
[basicdemo]: https://github.com/pallet/pallet-examples/blob/develop/basic/src/demo.clj "Basic interactive usage of Pallet"
[basic]: https://github.com/pallet/pallet-examples/tree/develop/basic/ "Basic Pallet Examples"
[screencast]: http://www.youtube.com/hugoduncan#p/u/1/adzMkR0d0Uk "Pallet Screencast"
[clojure]: http://clojure.org "Clojure"
[cljstart]: http://dev.clojure.org/display/doc/Getting+Started "Getting started with clojure"
[sonatype]: http://oss.sonatype.org/content/repositories/releases/org/cloudhoist "Sonatype Maven Repository"

[jclouds]: http://jclouds.org/ "jclouds"
[chef]: http://opscode.com/ "Chef"
[puppet]: http://www.puppetlabs.com/ "Puppet"
