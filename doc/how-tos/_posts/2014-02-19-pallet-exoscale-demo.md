---
layout: doc
title: Demo of webapp deployment on exoscale using Pallet
author: Antoine Coetsier
section: documentation
subsection: how-tos
summary: |
   This is a demonstration of using Pallet to deploy a test webapp on 
   exoscale cloud service. It will spawn a loadbalancer, webserver and
   database instances with all requirements: SSH keys, security groups,...
---

Deploy a test infrastructure on pallet. This demonstration set can be found on [github](https://raw.github.com/exoscale/pallet-exoscale-demo)

### Application:

The demo app is a simple yet usefull URL-Shortener. This shortener is a 3 tier application with the following layers:
* Load Balancer: NGINX
* Web Server: Python
* Database Server: Redis

It will make a store a hash of your long url to a exo.po/XXXXX patern 


### Deployer

To make things easier and to hide the pallet magic, we have created a web deployer, 
which is an web application that will control the URL-Shortener deployement and configuration. Once you run the project,
the deployer will be built and present you with the following interface:

![exoscale deployer]({{ site.cdn }}/{{site.asset-ver}}/images/exoscale-1.png)

### Use Case

Once running, you can deploy the URL-Shortener with:
* 1 LB
* 1 WEB
* 1 DB

Then to scale up for more trafic, you can specify:
* 1 LB
* 3 WEB
* 1 DB

Pallet will then spawn 2 new webserver instances, intall packages and URL-Shortener code and eventually reconfigure the NGINX 
LB to take in account the 2 new workers.

    root@lb-ac:~# cat /etc/nginx/conf.d/upstream-shorten.conf
    upstream shorten {
        server 185.19.28.65:8080;
    }

    root@lb-ac:~# cat /etc/nginx/conf.d/upstream-shorten.conf
    upstream shorten {
        server 185.19.28.83:8080;
        server 185.19.28.86:8080;
        server 185.19.28.65:8080;
    }

## Prerequisites

To run the demo, you will need:
* A working JVM environment
* The Leiningen http://leiningen.org/#install
* A valid exoscale account https://portal.exoscale.ch/register

## Configuration

You will need an OpenSSH format RSA key in `$HOME/.ssh/id_rsa`.
Add the following in `$HOME/.pallet/services/exoscale.clj`:

    {:exoscale {:provider "exoscale"
                :api-key "<API_KEY>"
                :api-secret "<API_SECRET>"}}

## Running

Simply run `lein run` in the project's directory and point
your browser to http://localhost:8080


