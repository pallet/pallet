---
title: Provisional 0.8 documentation
layout: reference
section: documentation
subsection: reference
summary: Provisional information for Pallet 0.8
---

# Namespace changes:

* The specs and top level `lift` and `converge` from `pallet.core` are now in
  `pallet.api`.
* `pallet.core/phase-fn` is now `pallet.api/plan-fn`
* the session lookup functions from `pallet.session` are now in `pallet.crate`.
* the parameter settings functions are now in `pallet.crate`.
* `pallet.utils/make-user` is now `pallet.api/make-user`. There is now also a
  `pallet.core.user/make-user` that takes a map rather than varargs, and doesn't
  provide defaults.
* `*admin-user*` is now in `pallet.core.user`.
* actions are now all defined in `pallet.actions`

# Crates

The crates are now written using the `defplan` (no call arguments) and
`def-plan-fn` (with call arguments) forms. These obviate the need for threading
of the session map.

Each form within a `defplan`, `def-plan-fn` or a `plan-fn` is now either a call
to a plan function or action, or a vector with two elements specifying a symbol
and a plan function or action call, where the symbol will be bound to the result
of the call.

The value of the last form will be used as the return value of the plan
function.

To call a function that is not a plan function or an action, wrap the call in a
`m-result` form.

# lift and converge

`lift` and `converge` now return operations. You can wait on the result of an
operation by `deref`ing it. The operation also supports the pallet.fsmop/Control
protocol. This provides `abort`, `status`, `complete?`, `failed?` and `wait-for`
functions. Using `status`, you can now follow how far pallet has got in the
execution of the `lift` or `converge`.

# Low level lift and converge

`pallet.core.operations` provide lower level implementations of `lift` and
`converge` that operate on sequences of node-maps, where a node-map is
essentially a `group-spec` with a :node key containing a value implementing the
pallet.node/Node protocol.

These operations allow you to specify the targets in a more flexible fashion
than their `pallet.api` counterparts.

Below this, there is `pallet.core.primitives` that provide various low level
finite state machines, which can be used to compose your own variotions of
`lift` and `compose` that have potentially different semantics.

At the bottom, `pallet.core.api` provides the base functions used to build the
primitives and the operations.
