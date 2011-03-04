#!/bin/bash

# start the release

if [[ $# -lt 2 ]]; then
  echo "usage: $(basename $0) previous-version new-version" >&2
  exit 1
fi

previous_version=$1
version=$2

echo "\nStart release of $version, previous version is $previous_version\n\n"

git flow release start $version

echo "\n\nChanges since $previous_version"
git log --pretty=changelog  pallet-$previous_version.. | tee git.log
echo "\n\nNow edit ReleaseNotes and README"

$EDITOR ../pallet-wiki/docs/ReleaseNotes.org
$EDITOR README.md
