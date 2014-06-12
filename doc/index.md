---
layout: doc
title: Documentation
section: documentation
---

Pallet is a node provisioning, configuration and administration tool.  It is
designed to make small to midsize deployments simple.

- [Overview](/doc/overview)
- [First Steps](/doc/first-steps)
- [Reference Documentation](/doc/reference)
- [How Tos](/doc/how-tos)
- [Crates](/doc/crates)
- [API Documentation](http://pallet.github.com/pallet/api/0.7/index.html)
- [Annotated Source](http://pallet.github.com/pallet/marginalia/uberdoc.html)

## [Latest Documentation Changes](/doc/changes) <small><a href="/doc/changes/atom.xml">RSS</a></small>
{% for post in site.categories.changes limit:5 %}
- {{post.date | date_to_string }} &raquo; [{{post.title}}]({{post.url}})
{% endfor %}
<p><a class="pull-right" href="/doc/changes"> See all the changes &rarr;</a></p>
<br>

## [Latest How-Tos Entries](/doc/how-tos) <small><a href="/doc/how-tos/atom.xml">RSS</a></small>
{% for post in site.categories.how-tos limit:5 %}
- {{post.date | date_to_string }} &raquo; [{{post.title}}]({{post.url}})
{% endfor %}
<p><a class="pull-right" href="/doc/how-tos"> See all the how-tos &rarr;</a></p>
<br>

## [Latest FAQ Entries](/doc/faq) <small><a href="/doc/faq/atom.xml">RSS</a></small>
{% for post in site.categories.faq limit:5 %}
- {{post.date | date_to_string }} &raquo; [{{post.title}}]({{post.url}})
{% endfor %}
<p><a class="pull-right" href="/doc/faq"> See all the FAQs &rarr;</a></p>
<br>
