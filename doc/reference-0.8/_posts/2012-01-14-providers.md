---
title: Providers
layout: reference
permalink: /doc/reference/0.8/providers
section: documentation
subsection: reference
summary: Pallet reference documentation for providers
follow: /doc/reference/0.8/node-push
apiver: 0.8
---

Pallet uses providers to create, start, stop and otherwise control nodes.  In
order to use pallet, you will need to specify a provider to use and your
credentials for that provider.

# Instantiating a Provider

We call an instance of a provider a `service`.  When instantiating a provider,
you will need to supply credentials, or other configuration information.

## Explicit Credentials

You can create a service explicitly, using the provider name, and your
credentials.

``` clojure
  (require 'pallet.compute)
  (def service
    (pallet.compute/instantiate-provider
     "aws-ec2"
     :identity "AKIAJ5QJ74DTMVR5DDS"
     :credential "mrLBIfHEbo8MI3lQfcODepp8EPrAWtSWxHYCj8V8"))
```

Pallet uses [jclouds](http://jclouds.org)' terminology, `identity` and
`credential`, but your cloud provider will probably use different
terms for these. Identity may be called username or key and credential
may be called password or secret.

## Credentials in config.clj

You can use the pallet configuration file `~/.pallet/config.clj` to specify
credentials.

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
or `converge`, you use
[`pallet.configure/compute-service`]({{site.baseurl}}/api/0.8/pallet.configure.html#var-compute-service).
By default, the first provider entry will be used, and you can specify an
alternative provider by passing the key to the function.

``` clojure
  (pallet.compute/service "rs")
```

The `~/.pallet/config.clj` file is read automatically by the `lein` plugin, and
in `lein`, you can switch between providers using the `-P` command line option.

``` bash
  lein pallet -P rs nodes
```


## Adding Credentials with the lein-pallet Plugin

The [pallet plugin](https://github.com/pallet/pallet-lein) for
[Leiningen](https://github.com/technomancy/leiningen) allows you to add service
definitions in `~/.pallet/services/`. For example,

``` bash
bash$ lein pallet add-service aws aws-ec2 \
  "AKIAJ5QJ74DTMVR5DDS" "mrLBIfHEbo8MI3lQfcODepp8EPrAWtSWxHYCj8V8"
```

would create the file `~/.pallet/services/aws.clj`, containing your credentials
for AWS EC2.

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
multiple other providers at once (eg. node-list and aws-ec2).

## Cloud Provider Names

The provider names pallet recognises (given the dependencies that have been
configured) can be displayed with
[pallet.compute/supported-providers]({{site.baseurl}}/api/0.8/pallet.compute.html#var-supported-providers)
at the REPL:

``` clojure
   (require 'pallet.compute)
   (pallet.compute/supported-providers)
```

From the command line, you can use the
[lein plugin](https://github.com/pallet/pallet-lein) to list providers:

``` bash
   lein pallet providers
```
