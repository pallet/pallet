---
title: Environment
layout: reference
permalink: /doc/reference/environment
section: documentation
subsection: reference
prior: /doc/reference/session
follow: /doc/reference/parameters
---

The environment is a map used for passing data and configuration choices into
pallet. The environment may be specified at various scopes.  The environment
contributed by each scope will be merged.

# Environment API

The environment can be accessed with
[`pallet.environment/get-for`](/pallet/api/0.7/pallet.environment.html#var-get-for).

# Pallet Specific Keys

Pallet recognises specific keys in the environment.

 `:algorithms`
: a map that can be used to specify specific algorithms to override the pallet defaults

 `:groups`
: a map, keyed on group name (keyword), that can specify node-spec details

 `:phase`
: a map of phase defintions, that all group-specs will extend

# Specifying Environment Values

## Config.clj

In config.clj, the environment may be specified at the top level, and/or at the
service level.

``` clojure
(defpallet
  ;; general environment
  :environment {:algorithms
                {:lift-fn pallet.core/parallel-lift
                 :converge-fn pallet.core/parallel-adjust-node-counts}}
  :services {:vb4
             {:provider "aws-ec2"
              ;; environment specific to :vb4 service
              :environment
              {:proxy "http://192.168.1.37:3128"
               :mirror {:apache "http://apache.mirror.iweb.ca/"}}}})
```

## `lift` and `converge` Arguments

Both [`lift`](/pallet/api/0.7/pallet.core.html#lift) and
[`converge`](/pallet/api/0.7/pallet.core.html#converge) can be passed an
environment map using the `:environment` keyword.
