---
title: First Steps with Pallet
layout: doc
permalink: /doc/first-steps
section: documentation
subsection: first-steps
summary: Quickstart instructions for creating a new Pallet project. Covers
         installation of lein and configuration of project.clj, and a primer
         on pallet clojure forms.
---

This guide walks you through provisioning and configuring your first
compute node in the cloud. The estimated completion time is 5 minutes.

## Requirements

* A computer with: 
  - Linux, OSX or Windows with [CygWin][cygwin]
  - Java 6 or 7 installed
  - A Secure Shell (SSH) client
* An active account in [Amazon Web Services][aws] (AWS)

[cygwin]:http://www.cygwin.com
[aws]:http://aws.amazon.com

## Installing Leiningen

For this exercise you need [Leiningen 2.x][lein], a build tool for
Clojure. Follow the [instructions][lein-instructions] provided by
Leiningen to install it on your local machine.

[lein]:http://github.com/technomancy/leiningen
[lein-instructions]: https://github.com/technomancy/leiningen#installation

## Creating a new project

Use Leiningen to create a new Clojure project named `quickstart`:

``` bash
bash$ lein new pallet quickstart
Created new project in: quickstart
bash$ cd quickstart
```

The `quickstart` directory created by this command contains the
scaffold of a project that uses Pallet.

## Configuring the cloud credentials

In order for Pallet to use your AWS account you need to add your AWS
credentials into the project's configuration. Use Leiningen to do so
now:

``` bash
bash$ lein pallet add-service aws aws-ec2 "your-aws-access-key" "your-aws-secret-key"
```

This command creates a `~/.pallet/services/aws.clj` file containing
your AWS credentials. Pallet will use these credentials when
interacting with AWS.

## Starting a Read-Eval-Print Loop (REPL) and loading Pallet

The next steps will be done at Clojure's REPL. Start a REPL with
Leiningen:

```bash
bash$ lein repl
```

Once you see the REPL's `user=>` prompt, issue the following command
to load Pallet:

``` 
user=> (require 'pallet.api 'pallet.compute 'pallet.configure)
nil
```

## Starting and configuring a cloud node

You are now ready to create your first AWS compute node. This section
builds and destroys a compute node running Ubuntu version 12.04. To do
so you first define a connection to AWS by entering the following at
the REPL prompt:

``` 
user=> (def aws (pallet.configure/compute-service :aws))
#'user/aws
```

Pallet will at this point verify if your credentials are correct by
establishing a connection to AWS. If the connection fails, you will
get an error message.

Now we need to define what kind of node we want to build. A
`group-spec` encapsulates this information. Define a group-spec named
`mygroup` as follows:

``` 
user=>
(def my-group
  (pallet.api/group-spec "mygroup"
    :node-spec (pallet.api/node-spec
                 :image {:os-family :ubuntu 
                         :image-id "us-east-1/ami-3c994355"})))
#'user/my-group
```

This group spec contains a `node-spec` that describes the
characteristics of the node to be built, in this case the AMI to use
and the OS that this AMI runs.

You can now instruct Pallet to create one of such nodes, by issuing:

``` 
user=> (def s (pallet.api/converge {my-group 1} :compute aws))
#'user/s
```

This command will create a compute node using the
`us-east-1/ami-3c994355` image (or AMI in AWS parlance) and named
"mygroup-1". You can build more than one node with the same
configuration in the same way.

The command above will also store in a variable `s` the results of
this operation, which we usually call _the session_. To view this
session use the function `explain-session` in `pallet.repl`. For
example, to see what Pallet did in the during the previous command, do
this:

```
user=> (use 'pallet.repl)
nil

user=> (explain-session s)
nodes created: 1
PHASES: 
GROUPS: mygroup
ACTIONS:
nil
```

Now shut down this node issuing the same command as before, but change
the `:count` value to zero (you can ignore the `:node-spec`):

``` 
user=> (def s (pallet.api/converge {my-group 0} :compute aws))
#'user/s

user=> (explain-session s)
nodes destroyed: 1
PHASES: 
GROUPS: 
ACTIONS:
nil
```

## Authorizing yourself on the nodes

The command in the previous section created a node but it didn't
perform any configuration of this node. Most notably, it did not
configure the node to let you connect to it via Secure Shell.

Pallet provides a way for you to log into the node by configuring the
node's SSH server thereby authorizing your SSH credentials in it.


The following commands update your `group-spec` with the SSH
configuration and your authorization, and then builds one node based
on this spec:

```
user=> (use '[pallet.crate.automated-admin-user :only [automated-admin-user]])
nil

user=> (def my-group 
  (pallet.api/group-spec "mygroup"
    :node-spec (pallet.api/node-spec
               :image {:os-family :ubuntu 
                       :image-id "us-east-1/ami-3c994355"})
    :phases {:bootstrap automated-admin-user}))
#'user/my-group

user=> (def s (pallet.api/converge {my-group 1} :compute aws))
#'user/s
user=> (explain-session s)
nodes created: 1
PHASES: bootstrap
GROUPS: mygroup
ACTIONS:
  PHASE bootstrap:
    GROUP mygroup:
      NODE 54.197.215.167:
        ACTION ON NODE:
          CONTEXT: [automated-admin-user: install]: 
          SCRIPT:
          | #!/usr/bin/env bash
          | set -h
          | echo '[automated-admin-user: install]: Packages...';
          | {
          | { debconf-set-selections <<EOF
          | debconf debconf/frontend select noninteractive
          | debconf debconf/frontend seen false
          | EOF
          | } && enableStart() {
...
 ```

The function `automated-admin-user` in the `:bootstrap` phase is the
one that performs all the SSH configuration we described. By default
it will authorize your local user's name and SSH credentials. Running
this function during `:bootstrap` ensures this configuration step only
takes place once per node, right after the node is first created.

What you see as a result of the `explain-session` call is the scripts
that Pallet executed on the node via SSH, and the result of such
executions.

From now on Pallet will only connect to your nodes using your local
user's SSH credentials.

### SSH credentials with a passphrase

If your SSH credentials are secured with a passphrase, you need to
have an SSH agent running on your local machine. You must also make
your SSH keys available to this agent. Pallet will then use this agent
to securely connect to your nodes without needing to ask you for your
passphrase.

Consult your operating system manuals about configuring the SSH agent
(enabled by default on Mac OSX and some Linux distributions). To
permanently add your credentials to the SSH agent database issue the
following at the shell:

``` bash
bash$ ssh-add -K your-private-key-file
```

### Connecting to your node

You should now able to log into your new node via SSH and even use
`sudo` to gain administrator privileges. Use the following command to
find your node's IP address:

```
user => (show-nodes aws)
===========================================================================================
:group-name | :primary-ip    | :hostname        | :private-ip    | :os-family | :os-version
=========================================================================================== 
mygroup     | 54.197.215.167 | mygroup-bc6052ed | 10.215.119.164 | :ubuntu    | 12.04      
===========================================================================================
```

And then issue the following command at the shell to log into your
node:

``` bash
bash$ ssh <node's primary-ip>
```

Note that you should not need to provide any further information (e.g.
password) in the command above.

## Installing software on your nodes

Now that you (and Pallet) are authorized to log into the node, you may
program Pallet to perform further software installation and
configuration on your behalf.

Pallet operates on nodes in _phases_. You may define and run as many
phases as you need. For now we'll use the `:configure` phase, which is
run by default.

The following command will add additional code to install `curl`
during the `:configure` phase, then updates the existing nodes with
the new configuration using `lift` instead of `converge`:

```
user=> (use '[pallet.actions :only [package]]
            '[pallet.api :only [plan-fn]])
nil

user=> (def my-group
 (pallet.api/group-spec "mygroup"
   :node-spec (pallet.api/node-spec 
                :image {:os-family :ubuntu 
                        :image-id "us-east-1/ami-3c994355"})
   :phases {:bootstrap automated-admin-user
            :configure (plan-fn (package "curl"))}))
#'user/my-group

user=> (def s (pallet.api/lift [my-group] :compute aws))
#'user/s

user=> (explain-session s)
PHASES: configure
GROUPS: mygroup
ACTIONS:
  PHASE configure:
    GROUP mygroup:
      NODE 54.197.215.167:
        ACTION ON NODE:
          SCRIPT:
          | #!/usr/bin/env bash
          | set -h
          | echo 'Packages...';
          | {
          | { debconf-set-selections <<EOF
          | debconf debconf/frontend select noninteractive
          | debconf debconf/frontend seen false
          | EOF
          | } && enableStart() {
          | rm /usr/sbin/policy-rc.d
          | } && apt-get -q -y install curl+ && dpkg --get-selections
          |  } || { echo '#> Packages : FAIL'; exit 1;} >&2 
          | echo '#> Packages : SUCCESS'
... 
          | #> Packages : SUCCESS
nil
```

The results of calling `explain-session` are telling you that no nodes
were created or destroyed, and about the operations Pallet has
performed on the existing nodes to install curl.

Proceed to verify that 'curl' has been installed by logging into your
node via SSH and issuing the following at the node's shell:

``` bash
~$ curl --version
curl 7.22.0 (x86_64-pc-linux-gnu) libcurl/7.22.0 OpenSSL/1.0.1 zlib/1.2.3.4 libidn/1.23 librtmp/2.3
Protocols: dict file ftp ftps gopher http https imap imaps ldap pop3 pop3s rtmp rtsp smtp smtps telnet tftp 
Features: GSS-Negotiate IDN IPv6 Largefile NTLM NTLM_WB SSL libz TLS-SRP 
```

## Next

Hopefully this has given you an overview of how to use Pallet from a REPL. 

To learn more refer to the following next steps:

- The generated 'quickstart' project contains some template code for
  defining and using group-specs. Explore the `src` directory to find
  these templates.

- The [Reference Documentation][ref-doc] provides detailed information
  about what you can do with Pallet and how it works.

- Pallet supports multiple infrastructure providers. Most of the
  public and private cloud providers are
  [supported][jclouds-providers] via [Apache jclouds][jclouds].

- For development purposes Pallet also supports using VirtualBox as a
  mini-cloud provider. Check out [pallet-vmfest][pallet-vmfest] to
  find out how to integrate VirtualBox into your workflow.

- To see more examples of using Pallet on other setups:
  [deploy webapp example][webapp-example]

- If you have any questions on using Pallet, please contacts us
  through [Pallet's mailing list][mailing-list] or via IRC at
  [#pallet][irc] on freenode.net.

[ref-doc]: http://palletops.com/doc/reference-0.8
[jclouds-providers]: http://jclouds.apache.org/guides/providers/
[jclouds]: http://jclouds.apache.org
[pallet-vmfest]: https://github.com/pallet/pallet-vmfest
[webapp-example]: https://github.com/pallet/example-deploy-webapp 
[mailing-list]: http://groups.google.com/group/pallet-clj
[irc]: http://webchat.freenode.net/?channels=#pallet
