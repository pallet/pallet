Unstable development branch

# 0.8.0-alpha.6

## Fixes

- Ignore nodes with no group name
  The pallet.core.api-impl/node-has-group-name? function was throwing a NPE
  when the node didn't have a group name.

- Improve propogation of :line metadata
  The :line metadata is used to track source line numbers, and needs manual
  propagation on forms modified by macros.

- Honour user's :sudo-user for script permissions
  When using :sudo-user in the admin user, ensure that generated scripts 
  have appropriate permissions.

- Fix use of hierarchy in defmulti-plan

- Updates packages when package source changes
  In the :package-source crate install method, update the packages when the
  package source changes.  This ensures packages from the updated or new
  package source are available.

- Fix tests for quoting changes in sudo commands
  References #170

- use single-quotes in lieu of doubles with passwd
  Fixes #170

- Add script user to ssh execute logging, and cleanup output logging

- Fix environment crate
  The environment variables would toggle between being set and removed on
  successive runs.

- Fix update-settings action

## Features

- Update stevedore and script-exec versions
  Update to stevedore 0.7.3 and script-exec 0.1.2.

- Reduce monadic function nesting levels

- Add target-flag? in pallet.crate
  Returns a delayed function predicate for whether a flag is set, suitable 
  for passing to pipeline-when.

- Add target logging context

- Provide a specialised state monad implementation
  The stack traces when using clojure.core.algo were unusable.  This 
  implementation tries to reduce stack depth, and to give reasonable names 
  to the bind functions.

- Add role->nodes-map
  Returns a map keyed on role where the values are a sequence of all nodes 
  providing the role.

- Add on-one-node macro
  The body of the on-one-macro will be executed on only one node out of all
  the nodes that have the given roles. If more than one role is specified,
  and there is at least one node that provides the intersection of all the
  roles, then first of any such nodes is used.

- Make target-flag? callable as a plan function

- Add target crate funtion
  Returns the denormalised map for the target node.

- Add assertf plan function
  Enables assertions in plan functions.

- Add support for NodeHarware protocol to node-list

- Add :allow-unsigned option to the package action
  Allow unsigned packages to be installed by specifying
  `:allow-unsigned true` in the package action.

- Add flag-on-change to package-source
  The flag allows taking an action based on whether the package sources 
  have changed or not.

- Add NodeHardware protocol

- Enable support for upstart in service actions

- Add update-settings action

# 0.8.0-alpha.5

- Enable retry of SSH connection attempts

# 0.8.0-alpha.4

- Put phase exceptions on the :exception key in the operation map
  pallet.core.api/throw-operation-exception can be used to throw an
  exception reported in the operation map.

- Remove unnecessary require of pallet.thread-expr

- Use script-exec instead of ssh- and local-transport

- Add :insecure and :ssl-versions to wait-for-http-status

- Add a logging context for the target node

- Fix execution order in specs using :extends

- Log live-test failures at debug

- Add hosts-for-role and sanitise localhost
  When the hostname is not in the list of ips provided for /etc/hosts, add
  it as a psuedonym for localhost (prevents sudo from complaining).

- Log return-value-expr at debug

- Log action context at info

- Add aws-ubuntu-12-04 image list to live-test
  Uses us-east-1/ami-3c994355

- Allow instance-id to be specified in assoc-settings action

- Add automatic wrapping in delayed if required

- Automatically delay arguments to actions
  Allow easy use of node return values.

- Only unpack remote-directory files when md5 changes
  Use the md5 of the tar or zip file to guard unpacking the archive.

- Remove warn level logging from remote-file-content

- Allow instance-id to be specified in assoc-settings action

- Fix environment crate
  Both branches were being used on shared environment distros

- Fix md5-url comparisons
  The md5 comparison using :md5-url was always failing due to the file path
  being included in the .md5 file.

- Add set-hostname to pallet.crate.etc-hosts

- Add pallet.crate/target-name

- Add isa? based dispatch to defmulti-plan

- Fix remote-file for links

- Merge pull request #169 from MerelyAPseudonym/patch-2
  Tweak docs

- Update to pallet-fsmop 0.1.4

- Clarify description of an "environment" in Pallet

- Remove tempfiles after running scripts remotely

- Fix use of package-source in crate-install

- Add a confirmation to add-apt-repository in pacakage-source

- Fix live-test
  Neither the environment nor existing nodes were being used when
  convereging nodes for a test.

- Ensure the state after a final plan function is verified
  Due to optimisations in clojure.algo.monads, the final state in a chain-s
  expression was not being verified.

- Add a debugf macro to pallet.debug

- Rename p.c.ssh-key/record-public-key to public-key and implement
  The public key is returned as a node value.

- Remove use of :default in settings
  This was getting confusing, since :instance-id might have been specified
  as nil, and was not getting converted to :default.

- Fix filtering of bootstrapped nodes on converge

- Switch to use for clojure.tools.logging

- Log phase contexts

- Fix argument evaluation for maps containing nil values

- Allow naming of plan-fn functions

- Add supplied arguments (eg :user) to the environment map
  The :compute, :blobstore, :user, :middleware and :provider-options
  arguments are all added to the environment map.

- Update pallet.debug functions to work in plan functions

- Fix package-source method in crate-install

- Fix merging of specs passed to :extends

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
