---
title: Crate Writing Guide
layout: doc
permalink: /doc/crate-writing-guide
section: documentation
subsection: crates
summary: Pallet documentation crate writing guide
---

You are of course free to write crates however you wish, but these are the
guiding principles used in the pallet core crates.

## Don't create large monolithic crates

There are probably as many ways of configuring a package as there are users of
that package. A monolithic function will either have limited flexibility, or
will have so many options as to render it difficult to use.  By providing
building blocks, for example a function for each configuration file required,
the crate user can decide which parts to use and how to combine them.  At the
same time these component functions can be used to build simplified installers
for common use cases.

## Configuration Files

There are many ways of generating the content of a configuration file, and
installing the content should be a reusable function.

## Prefer content generation to templates

Using templates for configuration files is certainly convenient, but providing
clojure based configuration generation functions ensures that all configuration
is available within clojure for querying, etc.

## Install paths, users, etc

In order to expose details of your crate install to other crates, you should
use the session :parameters to record paths, user names etc.

Information for consumption on the same node should go into the host parameters
using `assoc-for-target`, `update-for-target` and `get-for-target` in the
`pallet.parameters` namespace.

Information for consumption on the other nodes should go into the service
parameters using `assoc-for-service`, `update-for-service` and `get-for-service` in
the `pallet.parameters` namespace.

## Package based install

Package based installs are preferred.

- a function to install a package or a set of packages should be called `install`

- ensure the package manager is up to date by calling
  `(pallet.action.package/package-manager :update)`

## Source based install

Sometimes a source based install can be useful. This should be optional.

## Init Service

Not all crates will use an init service, but many packages install one.

The crate should wrap a provided init service in an `init-service` function that
forwards actions to `pallet.action.service/service`, so the name of the
service is encapsulated. This wrapper should also expose an `:if-config-changed`
option to make its actions condtional on a change in configuration.  The flag
should be set by any changes in configuration, eg. using the
remote-file `:flag-on-changed` option.

## Finally

Not all of the core crates live up to these, yet.
