Thanks for installing Buck!

In this quickstart project, the file ios/BUCK defines the build rules. 

At this point, you should move into the project directory and try running:

    buck build //ios:BuckDemoApp

or:

    buck build demo_app_ios

See .buckconfig for a full list of aliases.

To run the app in the simulator you can run:

    buck install --run demo_app_ios

Buck requires xctool to run apple tests.
If you don't have xctool installed already you can install it from brew:

    brew install --HEAD xctool

Once you have xctool installed you can run tests with Buck:

    buck test demo_app_ios

This information is located in the file README.md if you need to access it
later.
