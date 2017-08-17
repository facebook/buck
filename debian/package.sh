#!/bin/bash
mkdir -p build

cp buck.equivs build/
cp ../LICENSE build/
cp ../README.md build/
cp Changelog build/
cp ../buck-out/gen/programs/buck.pex build/buck
cd build && equivs-build buck.equivs
cd -
