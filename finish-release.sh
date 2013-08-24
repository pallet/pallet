#!/bin/bash

# finish the release after updating release notes


if [[ $# -lt 1 ]]; then
  echo "usage: $(basename $0) new-version" >&2
  exit 1
fi

version=$1

echo "finish release of $version"

echo -n "commiting release notes.  enter to continue:" && read x
git add ReleaseNotes.md README.md \
&& git commit -m "Update readme and release notes for $version" \
&& echo -n "Peform release.  enter to continue:" && read x \
&& mvn release:clean \
&& mvn release:prepare \
&& mvn release:perform \
&& mvn nexus-staging:release

mvn site

echo "=========================="
echo "Now update lein-pallet-new"
echo "            pallet-examples"
echo "            website"
echo "            push to gh-pages"
