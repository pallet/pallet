---
title: Providers
layout: reference
permalink: /doc/reference/providers
section: documentation
subsection: reference
summary: Pallet reference documentation for providers
follow: /doc/reference/node-push
---

Pallet uses providers to create, start, stop and otherwise control nodes.  In
order to use pallet, you will need to specify a provider to use and your
credentials for that provider.

# Providing Credentials

## Credentials in config.clj

You can use the pallet configuration file
[~/.pallet/config.clj]({{site.baseurl}}/doc/reference/config.clj) to specify credentials.

``` clojure
  (defpallet
    :services
      {:aws {:provider "ec2"
             :identity "key"
             :credential "secret-key"}
       :rs  {:provider "cloudservers"
             :identity "username"
             :credential "key"}})
```

The service key, `:aws` and `:rs` above, has to be unqiue, but you can have
multiple accounts for the same provider.

To create a compute service object from this file, that you can pass to `lift`
or `converge`, you use `pallet.configure/compute-service`. By default, the first
provider entry will be used, and you can specify an alternative provider by
passing the key to the function.

``` clojure
  (pallet.compute/service "rs")
```

The [~/.pallet/config.clj]({{site.baseurl}}/doc/reference/config.clj) file is read automatically
by the `lein` plugin, and in `lein`, you can switch between providers using the
`-P` command line option.

``` bash
  lein pallet -P rs nodes
```

## Explicit Credentials

You can log in to the cloud explicitly, using the provider name, and your
credentials.

``` clojure
  (require 'pallet.compute)
  (def service
    (pallet.configure/compute-service
     "provider" :identity "username" :credential "password"))
```

Pallet uses [jclouds](http://jclouds.org)' terminology, `identity` and
`credential`, but your cloud provider will probably use different
terms for these. Identity may be called username or key and credential
may be called password or secret.


# Available Providers

Pallet comes with a built-in `node-list` provider, that allows you to work with
servers that already exist, or a started outside of pallet. Other providers can
be made available by adding dependencies to your classpath.

Adding the [pallet-jclouds](https://github.com/pallet/pallet-jclouds) jar to
your project's dependencies gives access to
[jclouds providers](http://www.jclouds.org/documentation/reference/supported-providers/).
Each jclouds provider also has a specific jar that you will need to add (there
is an org.jclouds/jclouds-all dependency, if you want to add all the jclouds
providers).

Adding the [pallet-vmfest](https://github.com/pallet/pallet-vmfest) jar to your
project's dependencies allows you to access
[virtualbox](https://www.virtualbox.org/) as a local "cloud".

Finally, there is a built in `hybrid` provider, that allows you to talk to
multiple other providers at once (eg. node-list and aws).

## Cloud Provider Names

The provider names pallet recognises (given the dependencies that have been
configured) can be displayed with the following from the REPL:

``` clojure
   (require 'pallet.compute)
   (pallet.compute/supported-providers)
```

From the command line, you can use the lein plugin to list providers:

``` bash
   lein pallet providers
```
