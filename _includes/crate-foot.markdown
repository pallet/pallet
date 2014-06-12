### Dependency Information

{% assign repos = {sonatype: "https://oss.sonatype.org/content/repositories/releases/", clojars: https://clojars.org/repo} %}


<pre>
:dependencies [[{{page.group-id}}/{{page.artifact-id}} "{{page.version}}"]]
{% if "sonatype" == page.mvn-repo %}:repositories 
  {"sonatype" 
   {:url "https://oss.sonatype.org/content/repositories/releases/"}}
{% endif %}</pre>

### Releases

<table>
<thead>
  <tr><th>Pallet</th><th>Crate Version</th><th>Repo</th><th>GroupId</th></tr>
</thead>
<tbody>
{% for v in page.versions %}
  <tr>
    <th>{{ v['pallet'] }}</th>
    <td>{{ v['version'] }}</td>
    <td>{{ v['mvn-repo'] }}</td>
    <td>{{ v['group-id'] }}</td>
    <td><a href='{{page.git-repo}}/blob/{{page.tag-prefix}}{{v['version']}}/ReleaseNotes.md'>Release Notes</a></td>
    <td><a href='{{page.git-repo}}/blob/{{page.tag-prefix}}{{v['version']}}/{{page.path}}'>Source</a></td>
  </tr> 
{% endfor %}
</tbody>
</table>

For sonatype (versions 0.7.x and earlier) you will need to specify the sonatype
repository.

<pre>
:repositories
  {"sonatype"
   {:url "https://oss.sonatype.org/content/repositories/releases/"}}
</pre>
