#!/bin/bash

# copies templates from ../pallet.github.com to this project

SRC=${1:-../pallet.github.com}

echo "Copying layouts $SRC/_layouts/main into _layouts/main"
mkdir -p _layouts/main
cp $SRC/_layouts/main/* _layouts/main

echo "Copying includes $SRC/_includes/main into _includes/main"
mkdir -p _includes/main
cp $SRC/_includes/main/* _includes/main

echo "Copying navigation data from $SRC"
cp $SRC/_data/navigation.yml _data/navigation.yml

echo "done."
