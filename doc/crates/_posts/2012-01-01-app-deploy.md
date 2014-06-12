---
title: app deploy
layout: crate-ref
permalink: /doc/crates/app-deploy
section: documentation
subsection: crates
tags: [app-deploy-crate]
summary: A [pallet](http://palletops.com/) crate to install and control applications.
artifact-id: app-deploy-crate
group-id: com.palletops
version: 0.8.0-alpha.1
tag-prefix: 
versions:
  - {pallet: 0.8.0-beta.9, version: 0.8.0-alpha.1, artifact: , mvn-repo: , group-id: com.palletops, artifact-id: app-deploy-crate, source-path: src/pallet/crate/app-deploy.clj}
git-repo: https://github.com/pallet/app-deploy-crate
mvn-repo: 
path: src/pallet/crate/app-deploy.clj
---

[Repository](https://github.com/pallet/app-deploy-crate) &#xb7;
[Issues](https://github.com/pallet/app-deploy-crate/issues) &#xb7;
[API docs](http://palletops.com/app-deploy-crate/0.8/api) &#xb7;
[Annotated source](http://palletops.com/app-deploy-crate/0.8/annotated/uberdoc.html) &#xb7;
[Release Notes](https://github.com/pallet/app-deploy-crate/blob/develop/ReleaseNotes.md)

A [pallet](http://palletops.com/) crate to install and control applications.

## Usage

The `server-spec` function provides an easy way to deploy and control an
application.  It takes a map of options specifying the application artifact
sources and destinations, etc.  The name of the service for the application, and
the install directory under `:app-root` are both taken from the `:instance-id`
keyword.

The options are as described in the
[`settings`](http://palletops.com/app-deploy-crate/0.8/api/pallet.crate.app-deploy.html#var-settings)
function.

The `deploy` phase deploys using the first defined deploy method by default, but
can be passed an argument to specify the artifact source, as either `:from-lein`
or `:from-maven-repo`.

To control the application, `start`, `stop` and `restart` phases are defined, as
well as instacnce specific `start-<instance-id>`, `stop-<instance-id>` and
`restart-<instance-id>`.

The default supervision is with `runit`.


### Dependency Information

``` clojure
:dependencies [[com.palletops/app-deploy-crate "0.8.0-alpha.1"]]
```

### Releases

<table>
<thead>
  <tr><th>Pallet</th><th>Crate Version</th><th>Repo</th><th>GroupId</th></tr>
</thead>
<tbody>
  <tr>
    <th>0.8.0-beta.9</th>
    <td>0.8.0-alpha.1</td>
    <td>clojars</td>
    <td>com.palletops</td>
    <td><a href='https://github.com/pallet/app-deploy-crate/blob/0.8.0-alpha.1/ReleaseNotes.md'>Release Notes</a></td>
    <td><a href='https://github.com/pallet/app-deploy-crate/blob/0.8.0-alpha.1/'>Source</a></td>
  </tr>
</tbody>
</table>
