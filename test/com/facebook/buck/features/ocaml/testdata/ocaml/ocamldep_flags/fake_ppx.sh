#!/bin/bash

# The major limitation of ocamldep_flags is that it cannot use macros. ocamldep
# is run too early, before any dependencies are built. For this test, we'll
# simulate having a pre-built ppx binary available using this shell script.
#
# It's next to impossible to emulate the ppx interface, which takes in and
# outputs a marshaled AST. But we can emulate the -pp interface, which looks
# like
#
# my_pp <infile> # Output is written to stdout
#
# Specifically, it tries to emulate the ppx which finds [%transform_me expr] and
# replaces it with expr

INFILE=$1

sed "s/\[%transform_me \([^]]*\)]/\1/g" "$INFILE"
