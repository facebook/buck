Buck
====

Buck is a build tool. To see what Buck can do for you,
check out the documentation at <http://buckbuild.com/>.

[![Build Status](https://travis-ci.org/facebook/buck.svg)](https://travis-ci.org/facebook/buck) [![Build status](https://ci.appveyor.com/api/projects/status/v64qh0cd2cp9uto8/branch/master?svg=true)](https://ci.appveyor.com/project/Facebook/buck/branch/master)

Installation
------------

First, clone the Buck repository:

    git clone https://github.com/facebook/buck.git
    cd buck

Since Buck is used to build Buck, the initial build process invovles 2 phases:

##### 1. Bootstrap Buck with ant

    git clone https://github.com/facebook/buck.git
    cd buck
    ant

##### 2. Use bootstrapped version of Buck to build Buck:

    ./bin/buck build --show-output buck
    # output will contain something like
    # //programs:buck buck-out/gen/programs/buck.pex
    buck-out/gen/programs/buck.pex --help


License
-------
Apache License 2.0
