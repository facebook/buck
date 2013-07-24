#!/bin/bash

# This should throw an error because it tries to overwrite a file in BUCK_PROJECT_ROOT.
echo "corruption!" > $BUCK_PROJECT_ROOT/dummy_resource
