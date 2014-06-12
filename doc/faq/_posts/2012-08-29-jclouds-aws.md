---
layout: doc
title: FAQ Using AWS with the jclouds provider
section: documentation
---

# How do I run a node on an availability zone or region outside of us-east-1?

To run a node outside of us-east-1, you will need to specify a `:location-id` in
your node-spec. You will also need to ensure that your AMI is available in the
specified location.

``` clojure
:image {:image-id "us-east-1/ami-1aad5273"}
:location {:location-id "us-east-1a"}
```

# How do I run a node in a VPC?

To run a node inside a VPC, you will need to specify a `:subnet-id` in
your node-spec.

``` clojure
:location {:subnet-id "subxxxx"}
```

# Why does it take 20mins to list images?

When jclouds starts, it requests a list of images from the provider. On AWS,
this list is very long.  If you have DEBUG level logging to the console for
jclouds.wire, then this takes a very long time. The solutions is to set the log
level for jclouds.wire to INFO or WARN.

# How can I make jclouds see my AMIs?

Since AWS has a very long list of images, jclouds filters the image lists by
owner in order to improve performance. You can add your owner id to the list of
owners it uses, by specifying it in your service configuration in
`~/.pallet/config.clj` or `~/.pallet/services/*`, or when calling
`pallet.compute/compute-service`.

``` clojure
:jclouds.ec2.ami-query "owner-id=137112412989"
```

# How do I run my node in previously existing security groups?

``` clojure
:network {:security-groups ["my-security-group"]}
```

# How do I turn on Cloudwatch Monitoring when creating a node?

``` clojure
:qos {:enable-monitoring true}
```

See also
[Image Filters](http://www.jclouds.org/documentation/userguide/using-ec2/).

# How can I tell jclouds which credentials to use on bootstrap?

Normally jclouds can find the login credentials based on the information
provided in the AMI image.  For less frequently used images, it sometimes fails
to determine the image's login credentials, and you have to supply the relevant
information in the template.

``` clojure
(node-spec .... :image {... :override-login-user "ubuntu"})
```

Simple options here are `:override-login-user`, `:override-login-password`,
`:override-login-private-key`, and `:override-authenticate-sudo`.

Alternatively, you can pass a `org.jclouds.domain.LoginCredentials` object with
`:override-login-credentials` to set all of the above at once.  See
`org.jclouds.domain.LoginCredentials/builder` to construct an instance.

# How can I mount EBS volumes?

EBS volumes can be mounted using the `:block-device-mappings` argument in your
`node-spec` under the `:hardware` key.  The argument is a sequence of jcloud's
BlockDeviceMapping objects.  At the moment, there is no clojure wrapper for
creating these, but you can use the following jcloud's constructors, which are
static classes nested in `org.jclouds.ec2.domain.BlockDeviceMapping`:

```java
new MapEBSSnapshotToDevice(deviceName, snapshotId, sizeInGib,
                           deleteOnTermination)
new MapNewVolumeToDevice(deviceName, sizeInGib, deleteOnTermination)
new MapEphemeralDeviceToDevice(deviceName, virtualName)
new UnmapDeviceNamed(deviceName)
```

# See also

- [jclouds EC2 User Guide](http://www.jclouds.org/documentation/userguide/using-ec2/)
- [jclouds EC2 FAQ](http://www.jclouds.org/documentation/faqs/ec2-faq/)
