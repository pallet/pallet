---
title: Groups, Servers and Node Specs
layout: reference
permalink: /doc/reference/0.8/node-types
section: documentation
subsection: reference
prior: /doc/reference/0.8/node-push
follow: /doc/reference/0.8/phases
apiver: 0.8
---

The specification of a machine in pallet has several components.

The [`node-spec`](/pallet/api/0.8/pallet.api#var-node-spec) defines all the
choices needed to start up a node on a compute service. The
[`server-spec`](/pallet/api/0.8/pallet.api#var-server-spec) defines a set of
configuration functions to run on a node.  The
[`group-spec`](/pallet/api/0.8/pallet.api#var-group-spec) provides a name
that is used to map nodes to their configuration, and maps `server-spec`s to a
node.

## Node Specification

Starting a node on a compute service involves a myriad of choices, which are
encapsulated as a hash map by the
[`pallet.api/node-spec`](/pallet/api/0.8/pallet.api#var-node-spec) function.

The choices include the scale of hardware to be used (`:min-cores`, `:min-ram`),
the operating system image, the network connectivity, and quality of serivce
options.  The available keywords are compatible with those in
`org.jclouds.compute/build-template`.

``` clojure
(require '[pallet.api :refer [node-spec]])
(def mynodes
  (node-spec
    :image {:os-family :ubuntu :os-version-matches "10.10"}
    :hardware {:min-cores 2 :min-ram 512}
    :network {:inbound-ports [22 80]}
    :qos {:spot-price (float 0.03) :enable-monitoring true}))
```

An empty `:image` specification implies a default image will be used for the
nodes, usually the latest Ubuntu version, or CentOS if no Ubuntu images are
available.  When targeting AWS EC2, it is recommended to include an explicit
`:image-id` specifying the AMI to be used.

An empty `:hardware` specification implies the smallest hardware configuration
will be used.

## Server Specification

Server specs allow you to describe your infrastructure in a modular,
hierarchical manner.

The [`pallet.api/server-spec`](/pallet/api/0.8/pallet.api#var-server-spec)
lfunction is used to specify plan functions describing the installation,
configuration an control of a single component in your stack (e.g. nginx).  The
plan functions can be invoked using the
[`converge` or `lift` operations](/doc/reference/0.8/operations).

``` clojure
(require '[pallet.api :refer [server-spec plan-fn]])
(require '[pallet.actions :refer [package]])
(def with-wget
  (server-spec
   :phases {:configure (plan-fn (package "wget"))}))
```

The server specs can be extended.  In this example `with-wget-and-curl`
extends `with-wget`, and adds installation of curl:

``` clojure
(require '[pallet.api :refer [server-spec plan-fn]])
(require '[pallet.actions :refer [package]])
(def with-wget
  (server-spec
   :phases {:configure (plan-fn (package "wget"))}))
(def with-wget-and-curl
  (server-spec
   :extends [with-wget]
   :phases {:configure (plan-fn (package "curl"))}))
```

A default node spec can be added with the `:node-spec` keyword.

## Group Specification

A group-spec connects nodes to their configuration by defining a mapping from a
compute node's group-name (as returned by
[`pallet.compute/group-name`](/pallet/api/0.8/pallet.node#var-group-name)) to
a server-spec.

The group-spec is used to select the nodes targeted by a [`converge` or `lift`
operation](/doc/reference/0.8/operations).

A group-spec is created with the
[`pallet.api/group-spec`](/pallet/api/0.8/pallet.api#var-group-spec)
function.

``` clojure
(require '[pallet.api :refer [group-spec node-spec plan-fn server-spec]])
(require '[pallet.actions :refer [package]])
(def mynodes
  (node-spec
    :image {:os-family :ubuntu :os-version-matches "10.10"}
    :hardware {:min-cores 2 :min-ram 512}
    :network {:inbound-ports [22 80]}
    :qos {:spot-price (float 0.03) :enable-monitoring true}))
(def with-wget
  (server-spec
   :phases {:configure (plan-fn (package "wget"))}))
(def with-curl
  (server-spec
   :phases {:configure (plan-fn (package "curl"))}))
(def mygroup
  (group-spec
    "mygroup" :extends [with-wget with-curl] :node-spec mynodes))
```

The ability to extend multiple server-spec's allows you to easily compose
configuration.  As an example, you could define a server-spec for a `:configure`
phase to install tomcat according to your company standards, and a
`:restart-tomcat` phase to allow control of the tomcat service.  You could then
extend this for your server-spec to install hudson or other java web
application.

Alternatively, you can specify everything in one:

``` clojure
(require '[pallet.api :refer [group-spec node-spec plan-fn server-spec]])
(require '[pallet.actions :refer [package]])
(def mygroup
  (group-spec
    "mygroup"
    :phases {:configure (plan-fn
                         (package "wget")
                         (package "curl"))}
    :node-spec (node-spec
                :image {:os-family :ubuntu :os-version-matches "10.10"}
                :hardware {:min-cores 2 :min-ram 512}
                :network {:inbound-ports [22 80]}
                :qos {:spot-price (float 0.03) :enable-monitoring true})))
```

The group spec can be used to overide the default packager selection
(`:yum`, `:aptitude`, `:apt` or `:pacman`) with the `:packager` keyword.

## Cluster Specification

A `cluster-spec` allows you to add a prefix to a sequence of `group-specs`,
effectively allowing you to have multiple instances of your group spec's,
divided into logical clusters.

The `cluster-spec` can be the target of a
[`converge` or `lift` operation](/doc/reference/0.8/operations).
