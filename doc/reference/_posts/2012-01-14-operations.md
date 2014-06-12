---
title: Converge and Lift Operations
layout: reference
permalink: /doc/reference/operations
section: documentation
subsection: reference
summary: Pallet reference documentation for the converge and lift operations.  These
         two functions can be used to control the number of nodes in your cloud, and
         to apply configuration to them.
prior: /doc/reference/phases
follow: /doc/reference/crates
---
## Converge

The `converge` function can be used to adjust node counts and to apply phases.
The `:bootstrap` phase is the first phase applied to any new node that is
started, and `:configure` is always applied.  Additional phases may also be
specified. If the `:configure` phase is not explicitly passed, then it will
always be applied as the first phase (or second, after `:bootstrap` on new
nodes).

In this example we define a function that changes the number of running nodes
for the "mygroup" group.

``` clojure
  (require 'pallet.core)

  (defn scale-cluster [n]
    (pallet.core/converge
      (pallet.core/group-spec "mygroup" :count n)
      :compute (pallet.compute/service "aws")))
```

`converge` also accepts a :prefix keyword argument, which is applied to the
group names in the call.  This can be used to build job specific clusters.
In this example we scale load-balancer, web app and database nodes using
a single load balancer and twice as many web app frontends as database
backends.

``` clojure
  (require 'pallet.core)
  (def load-balancer (pallet.core/group-spec "lb"))
  (def web-app (pallet.core/group-spec "webapp"))
  (def database (pallet.core/group-spec "db"))

  (defn scale-cluster [prefix n]
    (pallet.core/converge
       {load-balancer 1
        web-app n
        database (inc (/ n 2))}
       :prefix prefix
       :compute (pallet.compute/service "aws")))
```

## Lift

The `lift` function is used to apply phases but does not change node
counts. The :configure phase is run by default only if no phases are explicitly
specified.
