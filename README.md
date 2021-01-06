<p align="center">
  <img src="https://buck.build/static/og.png" alt="logo" width="20%" />
</p>
<h1 align="center">
  Buck
</h1>
<p align="center">
  Buck is a build tool. To see what Buck can do for you, check out the documentation at http://buck.build/.
</p>

[![Build Status](https://circleci.com/gh/facebook/buck.svg?style=svg)](https://circleci.com/gh/facebook/buck)

Installation
------------

Since Buck is used to build Buck, the initial build process involves 2 phases:

##### 1. Clone the Buck repository and bootstrap it with ant:

    git clone --depth 1 https://github.com/facebook/buck.git
    cd buck
    ant

You must be using Java 8 or 11 for this to compile successfully. If you see compilation errors from ant, check your `JAVA_HOME` is pointing at one of these versions.

##### 2. Use bootstrapped version of Buck to build Buck:

    ./bin/buck build --show-output buck
    # output will contain something like
    # //programs:buck buck-out/gen/programs/buck.pex
    buck-out/gen/programs/buck.pex --help

##### Prebuilt buck binaries

Pre-built binaries of buck for any buck `sha` can be downloaded from `https://jitpack.io/com/github/facebook/buck/<sha>/buck-<sha>.pex`. The very first time a version of buck is requested, it is built via [jitpack](https://jitpack.io/). As a result, it could take a few minutes for this initial binary to become available. Every subsequent request will just serve the built artifact directly. This functionality is available for any fork of buck as well, so you can fetch `https://jitpack.io/com/github/<github-user-or-org>/buck/<sha>/buck-<sha>.pex`

For buck binaries built for JDK 11, modify end of the url to `buck-<sha>-java11.pex`.

Feature Deprecation
-------------------

Buck tries to move fast with respect to its internals. However, for user facing features (build rules, command line interface, etc), the Buck team tries to have a graceful deprecation process. Note that this generally applies only to documented functionality, or functionality that is less documented, but appears to be in wide use. That process is:

- An issue is opened on Github suggesting what will be deprecated, and when it will be removed. For larger features that are deprecated, there may be a period when the default is the new setting, and the old behavior may only be used with a configuration change.
  - [In-progress deprecation issues](https://github.com/facebook/buck/issues?utf8=%E2%9C%93&q=is%3Aopen+label%3Aannouncement+label%3Adeprecation) are tagged with 'announcement' and 'deprecation'
- A change is submitted to Buck that puts the old behavior behind a configuration flag and sets the default to the old behavior. These flags can be found at https://buck.build/concept/buckconfig.html#incompatible.
- For larger features, a change eventually is put in place that sets the default to the new behavior. e.g. when Skylark becomes the default build file parser.
- When the removal date is reached, a change is submitted to remove the feature. At this point, the configuration value will still parse, but will not be used by Buck internally.

License
-------
Apache License 2.0
