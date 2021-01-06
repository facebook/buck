# ideabuck
[ An IntelliJ plugin for working with `buck`-based code ]

## How to install it

Periodically, major/minor ideabuck release builds are posted 
to JetBrains...

  https://plugins.jetbrains.com/plugin/7826-buck-for-idea
  
...and can be downloaded/installed using the usual mechanisms.
Versions posted there have undergone reasonable amounts of in-house
testing and are almost certainly reasonable for general use.

Patch releases of ideabuck typically aren't published there,
to avoid being a distraction during high-churn periods of internal
development, although it is fairly straightforward for anyone with
a viable `buck` executable to build/install it locally.

## How to build ideabuck locally

The output of the target `buck//tools/ideabuck:ideabuck` is a jar file
suitable for installation as an IntelliJ plugin.  (For convenience, the
build alias `ideabuck` expands to this target.)

The ideabuck plugin is also compatible with Android Studio, and may work in
other JetBrains IDEs (like PyCharm, CLion, etc.).  To install it, take note
of the artifact location:

    buck build ideabuck --show-output
    
And from IntelliJ's Plugin Preferences, install the plugin from that file. 

If you wish, you may build it directly into your IDE's plugin directory.  For example,
on a Mac running IntelliJ 2018.3 Community Edition:

    buck build ideabuck --out ~/Library/Application\ Support/IdeaIC2018.3/ideabuck.jar

> Tip: To pick a commit where features are most likely to be stable
> and field-tested, look at the history of ideabuck's `plugin.xml`
> commit with a non-SNAPSHOT `<version>`.

## How to develop ideabuck

The ideabuck artifact is a standard IntelliJ plugin, built with `buck`.
To enable `buck` to compile against IntelliJ's jar files, a stripped down
version of IntelliJ's ABIs are stored in `buck//third-party/java/intellij/...`.

Note: to develop the plugin, one need not necessarily use (or have a copy of)
IntelliJ or `buck`, although pragmatically it is nearly impossible to do any
meaningful development without both.

#### Setup:
1.  Use `buck` to build ideabuck and its dependencies:  `buck build ideabuck`).
    This not only builds the ideabuck plugin, but also builds some `buck`
    artifacts that shared by both `buck` and ideabuck.
2.  Launch IntelliJ, open the `buck` project.
3.  Create an IntelliJ Plugin Development SDK called "IDEA SDK".  This can be
    done from the "Project Structure..." dialog, under "Platform Settings > SDKs".
4.  Import the IntelliJ module file at `tools/ideabuck/ideabuck.iml`.  

> Note: when `buck`'s dependencies change, you may have to repeat step (1).

#### Running/debugging:

Importing the `ideabuck.iml` module and defining an IntelliJ Plugin Development
SDK allows you to run/debug ideabuck using the usual method: create a "Plugin"
run configuration for the ideabuck module.

#### Testing

Testing ideabuck can be very tricky.  There are two categories of
tests:  "unit" tests which can be run in either Buck or IntelliJ, and
"integration" tests that only run in a full IntelliJ environment.

Continuous integration processes run the "unit" tests, but developers
must (currently) take responsibility for running the "integration"
tests locally, using an existing IntelliJ installation.

> Note: many of the "integration" tests are very unit-test-like.
> They live in the "integration" folder because they require the 
> actual IntelliJ implementations, which aren't present in the
> skeleton libraries actually checked into the buck repo.

