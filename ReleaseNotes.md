Unstable development branch

# 0.8.0-RC.1

## Features

- Add :default-phases to group and server specs

  Allows the specification of the default phases to be run for a server or group
  spec during a lift or converge.  Default phases are merged in an :extends
  clause, and are overridden by an explicit :default-phases clause.

  Implements #242

- Add extended inbound-port spec
  Allows specification of protocol and port ranges.

- Add :os-detect option to lift and converge
  This option can be used to control the detection of os-family and
  os-version on each node.  By default, os detection is enabled.

- packager reports based on detected os if needed
  When the packager is not returned by the node, and the node does not
  report an os-family and os-version, the use the detected os to determine
  the packager.

- Fall back on detected OS in with-script-for-node
  When the node doesn't report os details, take the information from the
  detected os details.

- Infer os-family and os-version from node
  When running a lift or converge, determine the os-family and os-version
  from the node, putting the result into the settings under the :pallet/os
  facility.

- Fall back on image info for os family and version
  When a node returns nil for os-family or os-version, try extracting the
  os-family and os-version from the group-spec.

- Add summary metadata to package actions

- Enable :debug options in converge and lift
  Adds :script-trace and :script-comments options in :debug to control the
  script generation.

  Closes #239

- Add :script-comments action-option
  The :script-comments option controls the generation of source line numbers
  in the generated script.

  Closes #241

- Make rsync options fully configurable

## Fixes

- Fail ppa install if add-apt-repository unavailable

- Don't set default ssh keys if password given
  In pallet.api/make-user, do not specify the default key paths when a
  password is passed.

- Fix argument order in lift-nodes

- Ensure admin-user is set and reported correctly
  The admin user should always be in the environment.

- Split out execute-on-unflagged
  Adds execute-on-filtered, execute-and-flag and makes execute-on-unflagged
  not set the flag on completion.

  Allow actions to be run on unflagged nodes without the setting of the
  flag.

- Make packager lookup map based
  This makes the lookup of packager based on os-family and os-version
  extensible.

- Factor VersionMap into p.core.version-dispatch
  Generalise the version map lookup and move to
  pallet.core.version-dispatch.

- Guard PPA addition with apt list file check
  Before installing a PPA, ensure its .list file doesn't exist in
  /etc/apt/sources.list.d.

- Fix looseness in node-spec contracts

- Add :apt to debconf script implementations

- Factor out obfuscated-passwords function

- Fix random failure in operations-test
  The "lift two phases for two nodes in a group" test was failing, but not
  reliably.  I think this was caused by the is condition in seen-fn failing.

- Quieten test output

- Implement run-nodes on node-list
  Ensure calling converge on a node-list service doesn't cause an exception.

  Fixes #244

- Changed short option for phases cmd line argument
  Fixes issue #9 (https://github.com/pallet/pallet-lein/issues/9) for pallet
  lein plugin.

  -p option is reserved for selecting provider

- Fix no-checkouts profile

- Add initial-plan-state to lift and converge result

- Fix lift-nodes for changed lift-partitions args

- Add environment to lift and converge results

- Fix incorrect namespace prefix in p.node

- Serialise :old-nodes in converge results
  The old nodes are not accessible after they have been removed, so
  serialise the node information before they are destroyed.

- Refactor node-map to pallet.node
  This avoids a circular dependency in pallet.core.operation if it tries to
  use pallet.core.data-api.

- Add p.c.data-api/session-data to serialize a session.

- Remove cake support from environment

- Wrap config file read exceptions to show path
  If an exception is thrown when reading a pallet config file, ensures the
  path of the config file appears in the exception message.

- Rename action-options key to ::action-options

- Move action-options into :plan-state
  This will allow action options to be passed into lift and converge.

- Remove :errors from results
  Use pallet.core.api/phase-errors to return a denormalised sequence of
  actions that failed.

- Remove :node-values from results

- Correct results on phase errors
  The results were not returned correctly when phase errors occurred.  This
  ensures all phases are reported, not just the failing phase.

  Updates to pallet-fsmop 0.3.0.

- Default rsync port from target node
  The ssh port is taken from the target node. Extra options can now be
  specified.

- Add limits-conf crate for configuring ulimits

- Fix p.live-test for new p.c.o/converge

- Allow compute-service to be nil in schema checks

- Add :literal support to system-environment
  Allow the use of shell expressions in the values passed to
  system-environment, without them being expanded.

  Fixes #158

- Add pallet-repl as a dependency
  If pallet-repl is not required, it can always be excluded.

- Add ssh credential functions
  Adds functions to verify and generate ssh credentials.

- Update to script-exec 0.3.5


# 0.8.0-beta.10

## Features

- Enable ssh-agent forwarding
  The :ssh-agent-forwarding action option can be used to enable ssh agent
  forwarding.

- Add context and action-symbol to result map

- Add crate-install-settings schema
  Provides a schema for checking install strategy keys in crate settings.

- Add pallet.crate/service-phases
  Function returns a map of service phases for service actions.

- Add remote-file-arguments schema check

- Remove pallet.repl
  This is now in its own project, pallet-repl.

- Add crate-install :archive method
  Can be used to install from tarball/zip.

- Add check-keys macro for map subset verification
  Can be used to check a selection of keys against a schema.

- Add config function in p.crate.ssh-key
  The config function can be used to manipulate ssh config file entries.

- Use :new-login-after-action in p.crate.environment
  Fixes #214

- Add :new-login-after-action action option
  The option is a boolean flag to indicate a the current ssh-session should
  be closed after the current action, to force a new login shell.

- Add blobstore function to pallet.crate

- Add local variables for clojure-mode indents

## Fixes

- Check a file has security context before updating it
  If a file does not have a security context then the context can not
  be updated.

- Fix schema predicate for :blob in remote-file-arguments

- Add missing :force to remote-file-arguments

- Fix requires in initd crate

- Allow :apt in :deb crate-install method

- Update local exec logging to match ssh

- Add debug logging to script-builder

- Ensure correct permissions under /var/lib/pallet
  The ownership and permissions under /var/lib/pallet should mirror the
  ownership and permission of the reflected filesystem, otherwise difficult
  to understand permission problems occur.

- Correct several namespace requires
  These were messed up by slamhound.

- Fix /var/lib/pallet path when using :script-dir
  When an action is executing, make the action map available in the session
  :action key, and use this to ensure the correct path is built under
  /var/lib/pallet.

  Fixes #238

- Fix mock-exec-plan to generate a valid group
  Fixes pallet.core.data-api/mock-exec-plan so that the group spec it
  generates matches the group-spec schema.

- Enable default metadata on phases from environment
  When phases are defined in the environment, this ensures that the phases
  have the default metadata applied to them.

- Split error handling between p.c.primitives & api
  The existing operations error handling (throw-operation-exception,
  phase-errors and throw-phase-errors) is moved to p.c.primitives, and the
  functions in p.c.api now take a result as argument.

- Factor out get-for into p.environment-impl

- Update service abstraction doc strings

- Make contract verification a runtime decision

- Add delayed-argument? predicate

- Normalize ns forms

- Add tests for contracts

- Fix update settings action for nil options

- Use default blobstore in task main-invoker

- Fix pallet.crate/compute-service and blobstore

- Add :blobstore to lift and converge docstring

- Allow blobstore-service to pass provider options

- Add no arg arities to *-from-config-file

- Allow options to pallet.actions/packages

- Fix pallet.crate/compute-service for no node case

- Log environment at TRACE in commit

- Propogate project map to :environment in up task

- Log :environment value when key not found

# 0.8.0-beta.9

## Features

- Add clj-schema checks for specs, user, converge and lift

- Add pallet.api/version for the pallet version
  Returns a map with :version and :revision keys.  When run from source,
  these will return :unknown.

- Add automated-admin-user to :bootstrap pallet.clj
  When reading groups from pallet.clj, add automated-admin-user and
  package-manager :update calls to :bootstrap if no :bootstrap phase exists.

- Add phases-meta, and implement :bootstrap with it
  This introduces the :phases-meta key used to add execution metadata to
  phases in server-spec and group-spec.

  The execute-on-unflagged-metadata and
  execute-with-image-credentials-metadata functions can be used to generate
  suitable metadata.

  The :bootstrap phase is tagged with metadata to make it run with the
  image-user, and only on nodes without a :bootstrapped flag.  Converge is
  refactored to exploit the metadata on the phase and simplify its
  implementation.

  Changes the environment-execution-settings and
  environment-image-execution-settings signatures in pallet.core.api.

## Fixes

- Update to fsmop 0.2.7 and script-exec 0.3.3

- Improve logging in spec-from-project

- Name anonymous function for merged phase functions

- Remove obsolete sessiion-with-environment

- Fix maybe-update-in for multi-argument case

- Ensure logged image-user passwords are obfuscated

- Enable phase specification of execution-settings-f

- Fix node-spec when called without options

- Remove environment option from cluster-spec

- Don't set :node-filter for group-name based groups
  Do not set the :node-filter on a group for groups based on :group-name
  membership, so that functions that modify the group-name don't have to
  worry about updating the :node-filter function.

- Correct error message for group without count

- Use assert for error message on unbound session

- Fix os-version in show-nodes

- Fix hostname keyword in show-nodes

- Fix missing space in script prolog

# 0.8.0-beta.8

## Features

- Add assoc-in-settings action
  This can be used to put a value into the settings at a specific path.  The
  value passed may be a node value, or some other delayed expression.

- Add node-, server-, group- and cluster-spec :type
  Allows for simple testing of whether a value is a node-spec, etc. The
  types are
  ::node-spec, ::server-spec, ::group-spec and ::cluster-spec in the
  pallet.api namespace.

- Add :default-service to task generated config.clj

- Parameterise lift and converge execution
  Adds the :partition-f, :post-phase-f, :post-phase-fsm and
  :phase-execution-f keywords to lift and converge.

  Enables the use of partitioning, post phase and phase execution functions
  on converge and lift.

- Make lift and converge synchronous by default
  pallet.api lift and converge revert to being synchronous by default.  Pass
  :async true to enable the return of an Operation and have call complete
  asynchronously.  Also adds :timeout-ms and :timeout-val, that control the
  timeout behaviour of the synchronous operation.

  This breaking change as against 0.8 beta's, and is a reversion to 0.7
  behaviour by default.

- Add group-nodes and lift-nodes in pallet.api
  These provide api functions for working at the node level, rather than
  just the group level.


## Fixes

- Fix channel connection failures on timeout
  JSch Session objects seem to timeout and still report that they are
  connected. This works around the issue by retrying on a "session is not
  opened." exception.

  Fixes #222

- Rename return-value-expr to with-action-values
  with-action-values is a clearer name.  return-value-expr remains, but is
  deprecated.

- Rename node-predicate to node-filter in group-spec

- Set node-filter in group-spec

- Ensure :default-service is respected in tasks
  Any default service that has been configure should take precedence.

- Use software-properties-common in quantal and up
  To install ppa: repositories, add-apt-repository is used. In quantal and
  above this has moved from python-software-properties to
  software-properties-common.

  Fixes #229

- Make :apt, :aptitude equivalent for package-source
  In package-source, and packages do not distinguish between :apt or
  :aptitude.

  Also fixes test for changing default to :apt rather than :aptitude.

- use :apt by default on Ubuntu

- Static tags for node-list provider
  Add a new tag-provider for the node-list provider enabling these nodes to
  have predefined static tags.

  The :bootstrapped tag is defined with the value `true` in order to avoid
  the bootstrap phase to be run on the `pallet up` action.

- Fix lift to pass all nodes as the service-state
  The new pallet.core.operations/lift was passing only the current target
  nodes as the service-state, which meant phases could only see nodes within
  the current target when using nodes-in-group, etc.

- Fixed DEBUG log exception for :admin-user without keypaths
  If the admin-user defined in the environment is defined with :username and
  :password and without private-key-path/public-key-path pair, when DEBUG
  log was turned on for pallet.main-invoker it failed with a
  NullPointerException.

  Now if values are nil empty string is logged instead.

- Add live test for pallet.core.operations
  Adds pallet.test-specs/operations-test with role :ops, which tests
  p.c.o/lift and p.c.o/group-nodes, and operating on individual nodes.

- Remove lift from pallet.core.operations/converge
  Factor out the lift from the tail of pallet.core.operations/converge, and
  make pallet.api/converge use pallet.core.operations/converge and
  pallet.core.operations/lift.

- Fix os-map-lookup
  The os-map-lookup was incorrectly using the string returned by os-version,
  without converting it to a version vector.


# 0.8.0-beta.7

## Features

- Allow service definitions from the classpath
  Service definitions can no be put in pallet_services/*, in the same form
  as they currently appear in ~/.pallet/services.  This allows project
  specific service definitions.

- Enable cluster-spec to add roles to its groups
  The :roles passed to the cluster-spec are inserted into its groups' roles.

## Fixes

- Mark image credentials as :temp-key
  When using image credentials to bootstrap, mark the credentials with
  `:temp-key true` so they are not added to the system agent.

  Fixes #226

- Update to script-exec 0.3.1
  Should fix issues with stale jsch Session objects being re-used.

  Closes #223.

- Fix merging of environment phases

- Fix lift task for updated project-groups arity

- Add back p.crate/groups-with-role

- Assert if update-in passed a nil function
  Also treats a nil argument to f-or-opts in update-settings as a map.

- Remove the set -x from remote file
  This can be enabled with the :script-trace action-option.

# 0.8.0-beta.6

## Features

- Add explain-phase
  Adds pallet.repl/explain-phase which can be used to see the action plan
  resulting from the execution of a phase from a server-spec (or
  group-spec).

- Enable passing of group-name in explain-plan

- Create a service supervision abstraction
  Create an abstraction that will easily allow crates to support running
  under a variety of service supervisors.  Provides a function to test
  supervisor implementations.

- Add wait-for-file action
  Allows waiting on the creation or removal of a file.  Also adds a
  wait-while script generator in p.script.lib.

- Allow verification of files before installation
  Adds a :verify option to remote-file, that can run an arbitrary program on
  the file on the node before it is installed.  If the verify program
  returns non-zero, the remote-file fails.

- Make script prolog extensible using script function

- Add deep-merge to pallet.utils

## Fixes
- Update to pallet-fsmop 0.2.6
  Fixes hangs caused by assertion failures.

- Write sectioned-properties in terms of name-values
  Enables passing of a map of maps, rather than just a vector of maps.

- Ensure :start when already running is not an error
  Calling pallet.crate.service/service with :action :start should not error
  if the process is already started.  This adds a test to ensure service
  implementations conform to this expectation.

- Fix script-test/testing-script to properly report PASS/FAIL.

- Add markers (==> and <==) to logs for scripts and their output.

- Fix group-name crate function

- Ensure :package-source install updates packages
  When using the :package-source install method, ensure that the
  package-update occurs before the package install if it is required.

- Fix zero arity nodes-in-group

- Add :release to package-source doc string

- Allow fragment in plan-when test expressions

- Refactor script-builder to use script fns
  Refactors the script-builder code to avoid any hard coded paths, leaving
  it open to customisation via script functions.  Allows possible
  customisation for systems with sudo, env, etc in unusual places.

- Fix add-service task for arity two and three

- Fix as-action
  as-action was not providing a state monad return value.  It now returns
  the value of its body and the input session as the monadic return value.

- Add rsync integration test

- Fix remote-directory with :local source


# 0.8.0-beta.5

- Update fsmop, stevedore, script-exec, and pallet-common

- Add :preseeds option to :packages install strategy
  The :preseeds key takes a sequence of debconf-set-selections maps.

- Add debconf-set-selections action
  The action can be used to set debconf selection values.  It runs before
  any
  `package` action.

- Add pallet.script.lib/file like c.java.io/file
  Adds a file function for concatenating path elements in script.
- Fix ssh-upload for no-sudo case

- Update :no-service-required tasks to take options
  Adds an initial option map to all no-service-required tasks.  This makes
  arg lists consistent across tasks, and allows no-service-required tasks to
  still use lein project information when invoked through pallet-lein.

- Fix exit status for testing-script

- Allow delete-local-path to take a File

- Fix cp script function long option format

- Remove md5 and backup files from target paths
  The md5 files and backup versions are now written to a parallel directory
  structure under /var/lib/pallet.

- Add precondition for debconf-set-selections

- Add action option to enable script tracing

- Don't swallow exceptions from ssh script execution

- Make local relative paths user home relative
  When running local script functions, relative paths should be resolved
  relative to the user's home directory, for consistency with ssh execution.

- Ensure asserts within plan functions are reported

- Create temp directories under TEMPDIR on osx

- Make md5 checking on os x more robust

- Correct the fixup of md5 files with paths

- Fix argument order in spec-from-project call

- When a task fails to read pallet.clj, show reason
  The exception thrown when pallet.clj failed to be read was being
  swallowed.  It is now logged at FATAL level before aborting.

- Remove reflection warnings

- Remove unused get-session function

- Remove unused cache implementation

- Remove outdated launch script generator

- Add exclusion to prevent Enlive from checking every Clojure version.

- Add image-user to node-list nodes


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
  When a task fails to load the project pallet.clj file, output a message
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

- Add target crate function
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

- Add a confirmation to add-apt-repository in package-source

- Fix live-test
  Neither the environment nor existing nodes were being used when
  converging nodes for a test.

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
  The lift* and converge* functions return the fsms used by left and
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
  permission.

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

- Fix converge for denormalised nodes

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
