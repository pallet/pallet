Unstable development branch

# 0.8.0-alpha.3

- Update to ssh-transport 0.1.1

- Make aptitude update quieter
  Fixes #160

- Update to pallet-fsmop 0.1.2

- Quieten p.actions.direct.settings-test

- Factor out lift* and converge* in pallet.api
  The lift* and converge* fuctions return the fsms used by left and
  converge respectively, without calling operate on them.

- Improve etc-hosts docstring and tests

- Add p.crate/get-node-settings and p.actions/assoc-settings
  Allow capture of node results into settings, and query of settings across
  nodes.

- Fix nodes-in-group
  Added to test in etc-hosts crate that exercises nodes-in-group via 
  hosts-for-group.

- Fix passing of environment to session in plan functions
  The environment was not being passed to the session.

- Merge pull request #154 from juergenhoetzel/develop
  Fix stack overflow error in supported-providers
- Fix remote-file-content

- Extend DelayedArgument to maps
  This allows return values to be passed as part of a map to actions.

- Add return-value-expr
  This macro allow the construction of a new action return value using the
  value of previous action return values.

- Fix send-text call to pass map for options

- Fix script mode change
  Forgot the pom change, and missed a send-stream call.

- If :sudo-user is set on a script action, make script readable
  When executing script with a different user, ensure the script file is
  readable by 'other'.

- Add a deprecated pallet.utils/make-user for compatibility

- Make sudoers a collected plan function

- Merge image credential with admin user
  The key reporting from jclouds getCredential has a bug, so we just use 
  the admin user's key

- Add def-collect-plan-fn
  Allow definition of collected plan functions

- Make live-test more robust to strings vs keywords in group names

- Fix stack overflow error in supported-providers
  This was caused by a mutual recursive invocation of supported-providers

- Add executed script to result map
  Ensure the executed script is available for debugging, logging, etc.

- Update assoc-settings to return values rather than session
  The session can get very large, and having it returned as the result
  makes debugging more difficult than it needs to be.

- Update top level lift and converge to cover 0.7.x use cases

- Fix node removal in p.c.operations/converge

- Fix p.c.o/node-count-adjuster to correctly remove nodes

- Only add a :errors key to the result when errors are present
  When grepping logs, it is annoying to have empty :errors values.

- Add version to pallet.core.api

- Fix p.c.o/lift and converge to accumulate phase results
  Only the last result was being returned.

# 0.8.0-alpha.2

- Use pallet-local-transport 0.1.1

- Update to pallet-fsmop 0.1.1

- Fix node removal in node-count-adjuster

- Rename multi-version-method and multi-version-plan-method
  Renamed to follow defmethod to defmethod-version and
  defmethod-version-plan

- Fix pallet.test-utils/with-null-defining-context
  This bug was hiding a slew of test failures.

- Add compatibility namespace for pallet.core

- Add package repo crate

- Add generic install methods for crates

- Fix live-test/build-nodes to process :targets correctly

- Fix os-family return assignment in minimal-packages

- Add missing dependency on useful

- Fix main-invoker test to not use the user's config.clj file

- Make build-session public, and ensure os-version is set on the node

- Add a precondition to version-vector

- Rename defmulti-version-crate to defmulti-version-plan and make
  defmulti-version-plan and multi-version-plan-method bodies now wrap their 
  bodies in a state monad pipeline.

- Update dispatch-version to provide better exception information

- Add map based datastructure with lookup based on os hierarchy
  The os-map and os-map-lookup functions implement a datastructure that is
  a map, where the key is a map and lookup is based on matching the :os key
  based on the os-hierarchy and the :os-version key on matching version
  ranges.

- Fix the environment crate to use pipeline-when

- Add defmulti-plan and defmethod-plan for multimethods in plan functions
  These functions implement a multimethod for the state monad, where the 
  dispatch function is also a monadic function.

- Fix propogation of plan-state in lift

- Fix merging of phase functions

- Fix rsync-directory action to not install rsync
  We would like to ensure rsync is installed, but this requires root
  permissions, and doesn't work when the action is run without root
  permision.

- Fix pipeline-when to correctly handle keywords used as functions

- Add simple script testing macros
  The pallet.scipt-test macros provide a simple framework for verifying 
  pallet phases on the node.

- Factor out NodeImage and NodePackager protocols
  Separate protocols facilitate multi-version support

- Fix api-test for updated service in node-list nodes

- Add image list for ubuntu precise

- Add core-user feature

- Add node tagging SPI
  Closes #139

- Fix converge for denormailsed nodes

- Fix pipeline-when for new stevedore test expression generation

- Remove running of :settings in p.c.operations/lift
  The :settings phase should not be handled specially in the core lift
  function.

- Represent service-state and targets as node maps
  At the core level, the information from the groups is denormalised into a 
  sequence of nodes. This decouples the lookup of phase functions from any 
  concept of group, allowing arbitrary organisation of node hierarchies
  (not limited to group-specs).

# 0.8.0-alpha.1

Initial alpha release.
