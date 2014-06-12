---
title: Groups, Servers and Node Specs
layout: reference
permalink: /doc/reference/node-types
section: documentation
subsection: reference
prior: /doc/reference/node-push
follow: /doc/reference/phases
---

The specification of a machine in pallet has several components.

The `node-spec` defines all the choices needed to start up a node on a compute
service. The `server-spec` defines a set of configuration functions to run
on a node.  The `group-spec` provides a name that is used to map nodes to
their configuration.

## Node Specification

Starting a node on a compute service involves a myriad of choices, which
are encapsulated as a hash map by the `node-spec` function.

The choices include the scale of hardware to be used (`:min-cores`, `:min-ram`),
the operating system image, the network connectivity, and quality of
serivce options.  The available keywords are compatible with those in
`org.jclouds.compute/build-template`.

``` clojure 
  (require 'pallet.core)
  (def mynodes
    (pallet.core/node-spec
      :image {:os-family :ubuntu :os-version-matches "10.10"}
      :hardware {:min-cores 2 :min-ram 512}
      :network {:inbound-ports [22 80]}
      :qos {:spot-price (float 0.03) :enable-monitoring true}))
```

An empty :image specification implies a default image will be used for the
nodes, usually the latest Ubuntu version, or CentOS if no Ubuntu images are
available.

An empty :hardware specification implies the smallest hardware configuration
will be used.

## Server Specification

A `server-spec` is used to specify phase functions, which can be invoked using
`converge` or `lift`.

``` clojure
  (require 'pallet.core)
  (require 'pallet.action.package)
  (def with-wget
    (pallet.core/server-spec
     :phases {:configure (pallet.phase/phase-fn
                           (pallet.action.package/package "wget"))}))
```

The server specs can be extended.  In this example `with-wget-and-curl`
extends `with-wget`, and adds installation of curl:

``` clojure
  (require 'pallet.core)
  (require 'pallet.action.package)
  (def with-wget
    (pallet.core/server-spec
     :phases {:configure (pallet.phase/phase-fn
                           (pallet.action.package/package "wget"))}))
  (def with-wget-and-curl
    (pallet.core/server-spec
     :extends with-wget
     :phases {:configure (pallet.phase/phase-fn
                           (pallet.action.package/package "curl"))}))
```

The server spec can be used to overide the default packager selection
(:yum, :aptitude or :pacman) with the :packager keyword.

A default node spec can be added with the :node-spec keyword.

## Group Specification

A `group-spec` connects nodes to their configration by defining a mapping from a
compute node's group-name (as returned by `pallet.compute/group-name`)
to a server-spec.

The group-spec is used to select the nodes targeted by a [`converge` or `lift`
operation](/doc/reference/operations).

``` clojure
  (require 'pallet.core)
  (def mynodes
    (pallet.core/node-spec
      :image {:os-family :ubuntu :os-version-matches "10.10"}
      :hardware {:min-cores 2 :min-ram 512}
      :network {:inbound-ports [22 80]}
      :qos {:spot-price (float 0.03) :enable-monitoring true}))
  (def with-wget
    (pallet.core/server-spec
     :phases {:configure (pallet.phase/phase-fn
                           (pallet.action.package/package "wget"))}))
  (def with-curl
    (pallet.core/server-spec
     :phases {:configure (pallet.phase/phase-fn
                           (pallet.action.package/package "curl"))}))
  (def mygroup
    (pallet.core/group-spec
      "mygroup" :extends [with-wget with-curl] :node-spec mynodes))
```

The ability to extend multiple server-spec's allows you to build easy to
compose configuration.  As an example, you could define a server-spec for
a :configure phase to install tomcat according to your company standards,
and a :restart-tomcat phase to allow control of the tomcat service.  You
could then extend this for your server-spec to install hudson or other
java web application.

Alternatively, you can specify everything in one:

``` clojure
  (require 'pallet.core)
  (def mygroup
    (pallet.core/group-spec
      "mygroup"
      :phases {:configure (pallet.phase/phase-fn
                           (pallet.action.package/package "wget")
                           (pallet.action.package/package "curl"))}
      :node-spec (pallet.core/node-spec
                  :image {:os-family :ubuntu :os-version-matches "10.10"}
                  :hardware {:min-cores 2 :min-ram 512}
                  :network {:inbound-ports [22 80]}
                  :qos {:spot-price (float 0.03) :enable-monitoring true})))
```
