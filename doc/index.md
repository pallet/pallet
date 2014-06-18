---
layout: doc
title: Documentation
section: documentation
---

Pallet is a node provisioning, configuration and administration tool.  It is
designed to make small to midsize deployments simple.

- [Overview]({{site.baseurl}}/doc/overview)
- [First Steps]({{site.baseurl}}/doc/first-steps)
- [Reference Documentation]({{site.baseurl}}/doc/reference)
- [How Tos]({{site.baseurl}}/doc/how-tos)
- [Crates]({{site.baseurl}}/doc/crates)
- [API Documentation]({{site.baseurl}}/api/0.7/index.html)

{% comment %} // dont' add this back until we have updates 
## [Latest Documentation Changes]({{site.baseurl}}/doc/changes) <small><a href="{{site.baseurl}}/doc/changes/atom.xml">RSS</a></small>
{% for post in site.categories.changes limit:5 %}
- {{post.date | date_to_string }} &raquo; [{{post.title}}]({{site.baseurl}}{{post.url}})
{% endfor %}
<p><a class="pull-right" href="{{site.baseurl}}/doc/changes"> See all the changes &rarr;</a></p>
<br>

## [Latest How-Tos Entries]({{site.baseurl}}/doc/how-tos) <small><a href="{{site.baseurl}}/doc/how-tos/atom.xml">RSS</a></small>
{% for post in site.categories.how-tos limit:5 %}
- {{post.date | date_to_string }} &raquo; [{{post.title}}]({{site.baseurl}}{{post.url}})
{% endfor %}
<p><a class="pull-right" href="{{site.baseurl}}/doc/how-tos"> See all the how-tos &rarr;</a></p>
<br>

## [Latest FAQ Entries]({{site.baseurl}}/doc/faq) <small><a href="{{site.baseurl}}/doc/faq/atom.xml">RSS</a></small>
{% for post in site.categories.faq limit:5 %}
- {{post.date | date_to_string }} &raquo; [{{post.title}}]({{site.baseurl}}{{post.url}})
{% endfor %}
<p><a class="pull-right" href="{{site.baseurl}}/doc/faq"> See all the FAQs &rarr;</a></p>

{% endcomment %}
<br>

