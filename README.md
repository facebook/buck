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

Since Buck is used to build Buck, the initial build process involves 2 phases:

##### 1. Bootstrap Buck with ant

    git clone https://github.com/facebook/buck.git
    cd buck
    ant

##### 2. Use bootstrapped version of Buck to build Buck:

    ./bin/buck build --show-output buck
    # output will contain something like
    # //programs:buck buck-out/gen/programs/buck.pex
    buck-out/gen/programs/buck.pex --help

##### Prebuilt buck binaries

Pre-built binaries of buck for any buck `sha` can be downloaded from `https://jitpack.io/com/github/facebook/buck/<sha>/buck-<sha>.pex`. The very first time a version of buck is requested, it is built via [jitpack](https://jitpack.io/). As a result, it could take a few minutes for this initial binary to become available. Every subsequent request will just serve the built artifact directly. This functionality is available for any fork of buck as well, so you can fetch `https://jitpack.io/com/github/<github-user-or-org>/buck/<sha>/buck-<sha>.pex`

License
-------
Apache License 2.0
