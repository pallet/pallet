#!/bin/bash

# finish the release after updating release notes


if [[ $# -lt 1 ]]; then
  echo "usage: $(basename $0) new-version" >&2
  exit 1
fi

version=$1

echo "finish release of $version"

echo -n "commiting release notes.  enter to continue:" && read x
( \
    cd ../pallet-wiki && \
    git add doc/ReleaseNotes.org && \
    git commit -m "Add $version release notes" && \
    cd -
) \
&& echo -n "Merging release notes.  enter to continue:" && read x \
&& git pull local-pallet-wiki master \
&& echo -n "Commit readme.  enter to continue:" && read x \
&& git add -u README.md && git commit -m "Update readme for $version" \
&& echo -n "Peform release.  enter to continue:" && read x \
&& mvn release:clean \
&& mvn release:prepare \
&& mvn release:perform \
&& mvn nexus:staging-close \
&& mvn nexus:staging-promote

