#!/bin/bash

# start the release

if [[ $# -lt 2 ]]; then
  echo "usage: $(basename $0) previous-version new-version" >&2
  exit 1
fi

previous_version=$1
version=$2

echo ""
echo "Start release of $version, previous version is $previous_version"
echo ""
echo ""

git flow release start $version || exit 1

echo ""
echo ""
echo "Changes since $previous_version"
git log --pretty=changelog  pallet-$previous_version.. | tee git.log
echo ""
echo ""
echo "Now edit ReleaseNotes and README"

$EDITOR ReleaseNotes.md
$EDITOR README.md
