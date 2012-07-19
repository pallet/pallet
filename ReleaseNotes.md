Unstable development branch

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
