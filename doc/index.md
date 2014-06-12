---
layout: doc
title: Documentation
section: documentation
---

Pallet is a node provisioning, configuration and administration tool.  It is
designed to make small to midsize deployments simple.

- [Overview]({{site.base-url}}/doc/overview)
- [First Steps]({{site.base-url}}/doc/first-steps)
- [Reference Documentation]({{site.base-url}}/doc/reference)
- [How Tos]({{site.base-url}}/doc/how-tos)
- [Crates]({{site.base-url}}/doc/crates)
- [API Documentation]({{site.base-url}}/pallet/api/0.7/index.html)
- [Annotated Source]({{site.base-url}}/pallet/marginalia/uberdoc.html)

## [Latest Documentation Changes]({{site.base-url}}/doc/changes) <small><a href="{{site.base-url}}/doc/changes/atom.xml">RSS</a></small>
{% for post in site.categories.changes limit:5 %}
- {{post.date | date_to_string }} &raquo; [{{post.title}}]({{post.url}})
{% endfor %}
<p><a class="pull-right" href="{{site.base-url}}/doc/changes"> See all the changes &rarr;</a></p>
<br>

## [Latest How-Tos Entries]({{site.base-url}}/doc/how-tos) <small><a href="{{site.base-url}}/doc/how-tos/atom.xml">RSS</a></small>
{% for post in site.categories.how-tos limit:5 %}
- {{post.date | date_to_string }} &raquo; [{{post.title}}]({{post.url}})
{% endfor %}
<p><a class="pull-right" href="{{site.base-url}}/doc/how-tos"> See all the how-tos &rarr;</a></p>
<br>

## [Latest FAQ Entries](/doc/faq) <small><a href="{{site.base-url}}/doc/faq/atom.xml">RSS</a></small>
{% for post in site.categories.faq limit:5 %}
- {{post.date | date_to_string }} &raquo; [{{post.title}}]({{post.url}})
{% endfor %}
<p><a class="pull-right" href="{{site.base-url}}/doc/faq"> See all the FAQs &rarr;</a></p>
<br>
