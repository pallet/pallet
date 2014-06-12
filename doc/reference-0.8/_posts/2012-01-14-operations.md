---
title: Converge and Lift Operations
layout: reference
permalink: /doc/reference/0.8/operations
section: documentation
subsection: reference
summary: Pallet reference documentation for the converge and lift operations.  These
         two functions can be used to control the number of nodes in your cloud, and
         to apply configuration to them.
prior: /doc/reference/0.8/phases
follow: /doc/reference/0.8/crates
apiver: 0.8
---

There are two high level operations in pallet, `lift` and `converge`.  Both
return an [operation map](#operations), that can be queried as the operation
completes asynchronously.

## Converge

The `converge` function can be used to adjust node counts and to apply phases.
The `:bootstrap` phase is the first phase applied to any new node that is
started, and `:settings` is always applied.  Additional phases may also be
specified. If the `:configure` phase is not explicitly passed, then it will
always be applied as the first phase (or second, after `:bootstrap` on new
nodes).

In this example we define a function that changes the number of running nodes
for the "mygroup" group.

``` clojure
  (require 'pallet.api)

  (defn scale-cluster [n]
    (pallet.api/converge
      (pallet.api/group-spec "mygroup" :count n)
      :compute (pallet.configure/compute-service "aws")))
```

## Lift

The `lift` function is used to apply phases but does not change node
counts. The :configure phase is run by default only if no phases are explicitly
specified.


## Operations

`lift` and `converge` are synchronous by default.  They have a `:timeout-ms`
option that can be used to specify a timeout for the operation in milliseconds
(in which case the value specified by `:timeout-val` is returned).

If passed `:async true`, they return operation maps, that implement the
[`Control`](http://pallet.github.com/pallet-fsmop/0.1/pallet.algo.fsmop.html#var-Control)
protocol.

The status of the operation can be monitored using the
[`status`](http://palletops.com/pallet-fsmop/0.1/pallet.algo.fsmop.html#var-status),
[`complete?`](http://palletops.com/pallet-fsmop/0.1/pallet.algo.fsmop.html#var-complete%3F)
and
[`failed?`](http://palletops.com/pallet-fsmop/0.1/pallet.algo.fsmop.html#var-failed%3F)
functions.

You can wait on the result of an operation by `deref`ing it.  In this case,
`deref` will re-raise any exception that occurs in the operation.

The
[`wait-for`](http://palletops.com/pallet-fsmop/0.1/pallet.algo.fsmop.html#var-wait-for)
function allows you to wait for the operation to complete, without re-throwing
exceptions.


Once the operation has completed, you can re-throw any exception with
[`throw-operation-exception`](http://palletops.com/pallet/api/0.8/pallet.core.api.html#var-throw-operation-exception).

If any plan function fails, the failures can be reported as a sequence of maps,
using the
[`phase-errors`](http://palletops.com/pallet/api/0.8/pallet.core.api.html#var-phase-errors)
function, or turned into an exception using
[`throw-phase-errors`](http://palletops.com/pallet/api/0.8/pallet.core.api.html#var-throw-phase-errors).
