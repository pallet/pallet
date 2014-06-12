---
layout: doc
title: How To Use Pallet With Existing Servers
author: Hugo Duncan
section: documentation
subsection: how-tos
summary: How to make Pallet work with a server rack or existing virtual machines.
         Creates a compute service using Pallet's node-list provider, specifies
         the SSH username and keys, and runs a simple lift to make sure everything works.
---

Pallet has a [`node-list`][node-list] provider, which you can use to connect
pallet to a server rack or to existing virtual machines. To talk to these
servers, you need to tell pallet IP addresses and which group they belong
to. You create a compute service by instantiating a [`node-list`][node-list]
provider with a sequence of nodes to provide that information.

## Getting a `node-list` compute service

There are two ways you can obtain a compute service.

### Instantiate directly

The group is used to match nodes to the [`group-spec`][node-types]s that you use
to define the configuration to apply.

``` clojure
(require 'pallet.compute)
(def my-data-center
  (pallet.compute/instantiate-provider
    "node-list"
    :node-list [["qa" "fullstack" "10.11.12.13" :ubuntu]
                ["fe" "tomcats" "10.11.12.14" :ubuntu]]))
```

or for pallet 0.7 or earlier:

``` clojure
(require 'pallet.compute)
(def my-data-center
  (pallet.compute/compute-service
    "node-list"
     :node-list [["qa" "fullstack" "10.11.12.13" :ubuntu]
                 ["fe" "tomcats" "10.11.12.14" :ubuntu]]))
```

### Instantiate based on `~/.pallet/config.clj`

If your nodes are fairly static, you may wish to just list them in
`~/.pallet/config.clj`.

``` clojure
(defpallet
  :services
  {:data-center {:provider "node-list"
                 :node-list [["qa" "fullstack" "10.11.12.13" :ubuntu]
                             ["fe" "tomcats" "10.11.12.14" :ubuntu]]})
```

You can then obtain the compute service using
[pallet.configure/compute-service][compute-service].

``` clojure
(require 'pallet.configure)
(def my-data-center
  (pallet.configure/compute-service :data-center))
```

## Assigning Nodes to groups

In the `:node-list` values above, the first value in each vector is a hostname,
the second value a group name, the third an IP address and the last is the
operating system distribution running on the node.

The group-name is used to match the nodes to a group-spec (passed to lift, see
below). This allows you to target different commands to different nodes.

## Specifying the SSH username and credentials

Pallet uses SSH to talk to your nodes. The default is to use your local username
and your id_rsa key, and assumes that your account on the nodes has
password-less sudo set up.

The credentials for ssh are specified using a user map, which can be constructed
using [`pallet.utils/make-user`][node-push]. This user map can be passed to
[`lift`][operations].

## Running a command on each node

To test your configuration, trying running a simple `ls` command.

``` clojure
(require 'pallet.actions 'pallet.api)
(pallet.api/lift
 (pallet.api/group-spec
  "tomcats"
  :phases {:configure (pallet.api/plan-fn
                       (pallet.actions/exec-script ("ls")))})
 :compute my-data-center)
```

or for pallet 0.7 or earlier:

``` clojure
(require 'pallet.action.exec-script 'pallet.phase)
(pallet.core/lift
 (pallet.core/group-spec
  "tomcats"
  :phases {:configure (pallet.phase/phase-fn
                       (pallet.action.exec-script/exec-script ("ls")))})
 :compute my-data-center)
```

[node-types]: http://palletops.com/doc/reference/node-types "Defining server and group-specs"
[node-push]: http://palletops.com/doc/reference/node-push "Configuring SSH credentials"
[operations]: http://palletops.com/doc/reference/operations "Lift and Converge"
[node-list]: http://palletops.com/pallet/api/0.6/pallet.compute.node-list.html "node-list API"
[compute-service]: http://palletops.com/pallet/api/0.6/pallet.configure.html#var-compute-service "pallet.configure/compute-service API doc"
