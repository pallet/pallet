Unstable development branch

# 0.8.0-beta.4

- Add :no-service-require metadata to project-init
  The task should not try to create a compute service.

- Add pallet.repl functions for easier crate development
  Add show-nodes, show-group for pretty printing the nodes in a provider or
  in a group within a provider

  Add explain-plan to show (print) what pallet is going to do with a plan
  function, both in terms of generated actions forms and also shell scripts.

  Add pallet.core.data-api to hold the data-only version of such functions.

- Add support for os-version in explain-plan
  Refactor how the default node is handled to make it more consistent and
  outside of pallet.core.data-api

- Allow action plans to be returned and not executed
  Implements an action-plan-data executor that can be used to return the 
  action-plan as a sequence of maps.  You can specify the printer by passing
  ':environment {:algorithms {:executor action-plan-data}}' to lift.

- Update to chiba 0.2.0

- Update to pallet-fsmop 0.2.3
  Fixes a compilation issue with clojure 1.5.0.

- Clean the imports in pallet.repl and done by p.repl/use-pallet

- Remove outdated forwards to p.configure from p.utils

- allow specification of environment vars with :script-env
  The :script-env can be specified in with-action-options.

- Add removal of path in md5 file normalisation
  When a .md5 file contains a path, md5 normalisation now removes the path 
  elements.

- Add logging of system properties in main-invoker

- Add error when crate install fails to dispatch

# 0.8.0-beta.3

- Add log-script-output macro to actions

- Default local script to run without sudo
  When running local scripts, do not use sudo by default.  Actions can ask
  for sudo by using `:exec-prefix :sudo` in their implementation metadata.

- Don't wrap FileNotFoundException in read-project
  This allows callers to catch the FileNotFoundException and not have to
  introspect an ex-info.

- Adjust visibility of core api functions
  The phase-args and target-phase functions are useful when adding
  target-action-plan methods.

- Enable user with private key strings
  Fixes the case where AMI's returning literally keys would cause bootstrap
  to fail.

- Update fsmop
  Adds running? predicate and three-value returns for failed? and complete?
  Adds timeouts to deref and wait-for.

- Update to script-exec 0.2.2

## Fixes

- Adjust log levels

- Fix hostname in script error logging

- Load plugins on converge or lift
  Load plugins and call the plugin init function if it exists.

- Fix service-script-path methods to use fragment
  The generated comments were breaking scripts.

# 0.8.0-beta.2

## Changes

- Simplify compute-service
  This aims to remove some of the confusion around the compute-service
  functions.

  This makes p.api/compute-service forward to p.configure/compute-service.

  p.configure/compute-service no longer tries to instantiate a provider,
  and is limited to looking up service configurations.

  p.compute/compute-service is renamed p.compute/instantiate-provider.

- Allow up task to take phase arguments

- Add group name and role selectors to pallet.clj

- Add a localhost compute service provider
  The localhost provider enables localhost to masquerade as a node in any
  group. Calling `run-nodes` on a localhost service will change the group
  name of the localhost node.


## Fixes

- Fix remote-directory for :local-file
  The generated temp file previously required unprivileged write
  permissions in the target directory.

  Fixes #199

- Document execution order for extended phases

- Ensure nodes taggable before setting state flags

- Fix halting of phases on error
  Phases were not stopping on action errors.

- Use primary-ip address for host on hostname change
  When changing hostname we ensure the hostname is in the hosts file, so
  sudo does not break.  Using the public ip ensures reverse name lookup is
  useable in hadoop, for example.

- Ensure context strings are on a single line
  This is so the script status markers are single lines.

- Add service-properties and image-user to node-list

- Fix reporting on missing provider

- Fix providers task to use support-providers

- Fix test failures introduced in 88b48b01b

- Fix pallet.clj phase and group-name handling

- Quieten downloads when using wget

- Fix phase declaration in with-automated-admin-user

- Add with-automated-admin-user to project refers
  Makes with-automated-admin-user available unqualified in pallet.clj
  files.

- Fix parens in sample and default project resources

- Fix md5sum script generation

- Fix project-init message when no project found


# 0.8.0-beta.1

- Remove dependency on CompilerException

- Add dependency message on project file failure
  When a task fails to load the projec pallet.clj file, output a message
  that explains that a dependency needs to be added.

- Change group ID to com.palletops, pom.xml, and lein project.clj

- Update for stevedore with resolved function position
  Fixes #164.

- Fix message formatting in project-init task

- Throw on unsupported clojure version
  Adds a top level form to pallet.task to throw when using an unsupported
  version of clojure.  This should give a less cryptic error message in
  that situation.

- Fix pallet.clj project file creation
  The creation of parent directories was incorrect.

- Add back enlive dependency

- Update exception message for some macro
  Update exception message for def-aggregate-plan-fn and
  def-collect-plan-fn.

# 0.8.0-alpha.9

- Update p.c.api/phase-errors to use wait-for

- Add support for connecting to nodes via a proxy.  Adds the
  pallet.node/NodeProxy protocol, with a single function, proxy, that
  returns a map with :host and :port keys.

- Allow injection of service definitions in tasks
  Adds pallet.main/add-service, to allow service definitions to be added by
  a task driver (eg. lein pallet plugin)

- Add edn output in nodes task
  The nodes task can be invoked with `-f edn` to output a clojure data
  structure describing the nodes.

- Update task option processing to use tools.cli

- Remove the new-project task

- Add project-init task to write a default pallet.clj
  Make it easy to generate an initial configuration file.

- Add pallet.api/print-nodes and pallet.api/print-targets
  Add a function to print the targets of an operation in a table.

- Update tasks to throw on error
  This is to match lein 2 expectations.

- Add up and down tasks
  The tasks bring up and tear down nodes according to the current
  project.clj file.

- Use project config in lift and converge tasks
  When no group-specs are specified in these tasks, use the project
  `pallet.clj` file to find a default group spec.

- Add project specific config file
  A project specific `pallet.clj` file is now available.  The file is in
  the project root by default.  The `pallet.project*` namespaces deal with
  loading and returning information from the file.  See
  `sample-project-pallet.clj` for an example.

- Only add a dash in cluster-spec if prefix given
  If a cluster-spec is requested with a blank prefix, do not add a dash to
  the group names.

- Fix processing of groups in converge
  Groups specified as a sequence with :count keys were being ignored.

- Rename test-resouces to dev-resources
  Aligns with lein 2 usage

- Fix the handling of task args
  The args were all being read, which caused problems when values passed
  didn't read to the expected type (eg. strings containing a single / were
  read as symbols). It is now the task's responsibility to do any reading
  required. Fixes #161.

- Pass explicit user for loopback tests

- Add :no-sudo to user for loopback tests

- Update for new source line comments in stevedore

- Adjust script execution logging
  Uses the new "#> " status line prefix in stevedore to help make the logs
  more searchable.

- Add function to throw operation phase errors
  Adds the pallet.core.api/throw-phase-errors function that will throw an
  exception if the passed operation has phase errors.

- Add location information to exec-checked-script
  In order to improved the utility of the logs, add file and line number to
  message string passed to exec-checked-script.

- Require clojure 1.4.0
  Remove use of slingshot

- Fix plan-when script generation bug
  plan-when was generating invalid script, due to unquote not being scoped
  correctly.

- Annotate functions returning a node-value
  Add {:pallet/plan-fn true} metadata to each function that returns a
  node-value.

- Don't show null as context in log messages
  Avoid showing anything as the context when no context is available.  This
  was being displayed as 'null'.

- Add arguments to phase functions
  Allow functions with arguments as phase functions.  When calling lift,
  the arguments to a phase can be passed using a vector.  For example (lift
  mygroup
  :phase [[:configure :full]]) passes a :full argument to the :configure
  phase function.

- Fix has-state-flag? for when node is not taggable

- Allow :packager specification in a group-spec
  This should fix a regression compared with 0.7.x.

- Update the user and group action doc strings

- Refactor etc-hosts crate
  The host entries are now maintained in the settings, with functions to
  add localhost, ipv6-aliases and a named localhost-hostname (using
  127.0.1.1). The aliases for each ip are now stored in a sequence.

  The new add-hosts function allows for bulk update of the hosts mapping.

  The hosts-for-group and hosts-for role are removed.  Equivalent
  functionality can be achieved using pallet.crate/nodes-with-role and the
  add-hosts function.

- Add trace level logging to pallet.ssh.execute
  The trace level logging gives insight into the timing of action
  executions.

- Add targets and target-nodes to pallet.crate
  These functions return all nodes being targeted by the converge or lift.

- Ensure a session is set when executing
  The thread-local *session* wasn't being set when executing an
  action-plan.

- Improve service-properties doc string

- Use a phase-context for named plan-fns
  When a name is given to the plan-fn, create a phase-context for it,
  rather than a session-context.  The session-pipeline is renamed to
  session-context.

- Make rsync action take options
  Allows passing a map of option flags.  By default rsync is now less
  verbose, with no -P flag.

- Add pallet.actions/as-action
  Allow wrapping of arbitrary clojure code to be run as an action.

- Improve error logging

- Update to latest fsmop and script-exec

- Update useful version


# 0.8.0-alpha.8

- Rename pipeline-when to plan-when

- Rename def-plan-fn to def-plan

- Rename phase-pipeline to phase-context

- Replace monadic plan functions with a dynamic var
  Change plan-fn to use ordinary clojure functions, and maintain state
  using a dynamic var.

# 0.8.0-alpha.7

## Fixes
- Resolve inconsistent Mac OS X naming
  `pallet.compute/os-hierarchy` refers to Mac OS X with `:osx`. The rest of
  the code in Pallet uses `:os-x`. I don't have a strong opinion on which
  to use, but altering `os-hierarchy` seems to be the simplest fix.

- Fixing a duplicate define issue for the lift function
  When compiling the code from scratch there was a duplicate define as the file
  was using lift from pallet.api and defining a lift function in there as
  well. The solution used was to rename the pallet.api to be lift2

- Removing a use reference to user that was not used in the file
  When compiling from a clean build I got a could not find the
  pallet.action.user reference. Since the reference was not being used, I just
  removed it

- Reduce log level on providers that fail to load

## Features

- Add compute-service-properties
  Allow reflection on the compute service properties within crate
  functions.

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
