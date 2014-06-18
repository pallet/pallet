---
title: Project Configuration File
layout: reference
permalink: /doc/reference/0.8/project-configuration
section: documentation
subsection: reference
summary: Description of the pallet.clj project configuration file
apiver: 0.8
---

The `pallet.clj` project configuration file allows you to specify project
infrastructure in a form that can be checked into your source repository.  The
file describes the [group-specs and node-specs]({{site.baseurl}}/doc/reference/0.8/node-types)
you wish to use with the project.  This allows a developer to check out your
project, and run `lein pallet up`.

## Getting started

Working with project files is easiest using the pallet plugin for lein.  Add the
following in your `~/.lein/profiles.clj` file:

``` clojure
{:user {:plugins [[com.palletops/pallet-lein "0.6.0-beta.5"]]}}
```

To create a default configuration file, run `lein pallet project-init`, which
will create a `pallet.clj` file.

We will also need a pallet service configuration.  By default `lein pallet` will
look for virtualbox, but you can generate an explicit service descriptor with:

`lein pallet add-service vbox vmfest`

The `pallet.clj` file tells `lein pallet` about the specifics of your project.

## Combined Application and Deploy Project

The `pallet-lein` plugin assumes by default that you will put your pallet code
in `pallet/src/` rather than `src`, so that you can keep your pallet code
separate from your application code, but still have both in a single git
repository.

You can then add your group-specs under the groups key.

``` clojure
:groups [(group-spec "my-group" :extends [with-automated-admin-user])]
```

Working this way makes sense when you have a standalone application.

## Standalone Pallet Project

To work with the `pallet-lein` plugin and a `pallet.clj` file, you'll need to
tell `pallet-lein` to add `src` to its classpath.

``` clojure
:pallet {:source-paths ["src"]}
```

You can then add your group-specs by adding a `require` on your namespace
containing you group-specs, and adding them under the `:groups`.  For example,
if you have a group spec declared with `def` at `myns.groups/dev-group-spec`,
then you can include it like this:

``` clojure
(require '[myns.groups :refer [dev-group-spec]])

(defproject myproject
  :groups [dev-group-spec])
```

Working with standalone pallet projects makes sense when you are writing crates
or other code that will be used as a dependency in other pallet projects.

## pallet.clj Options

Take a look at the
[sample project](https://github.com/pallet/pallet/blob/develop/sample-project-pallet.clj)
for an annotated description of the options available.


This assumes you have a project with a
[leiningen](https://github.com/technomancy/leiningen) `project.clj` build.
