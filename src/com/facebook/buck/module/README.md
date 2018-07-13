# Overview

Buck modules represent stand-alone packages with some Buck functionality that are relatively
independent from the rest of Buck code base and have explicit dependencies on other modules.

Buck modules framework is built on top of plugin framework and Buck modules are essentially plugins
that are loaded in their own class loaders. The modules have access to the base Buck functionality,
but they need to declare explicitly other plugins in the dependencies in order to access
functionality from those plugins.

# Migration to module architecture
Isolating code in independent modules usually improves the general architecture of the application
and removes unnecessary coupling among unrelated classes.

At the same time, moving code to modules can introduce some restrictions which need to be thought
through before migrating to modules.

## Dependencies between modules and base Buck functionality

Code in modules can only depend on core Buck functionality or explicitly declared dependencies.
At the same time, code in core Buck classes cannot depend on modules (except through plug-able
points). This means that the migration process should start with independent code that injects its
functionality through injection mechanism provided by the plugin framework. A good example is
`ZipRulesModule` that adds `zip_rule` build target through a custom `DescriptionProvider`
(`ZipDescriptionProvider`).

## Duplicate classes in modules

Another problem of the module architecture is duplicating classes in multiple modules. Buck modules
are packaged as jars and it's possible to create a configuration when multiple modules contain
the same class. This situation can lead to hard to debug bugs and increase in the size of final
binary. Right now we have a test that verifies that modules do not share classes
(`tools/build/modules:find_duplicate_classes_in_jars_test`).

# How to migrate to Buck modules

There are multiple steps required to move existing functionality to a module.

## 1. Refactor

Refactor your target code to be as independent as possible from the rest of code base.

## 2. Create target for a module

Use `buck_module` build rule to create a target that is going to represent the new module.

Here's the target definition for `ziprules` module:

```
load("//tools/build_rules:module_rules.bzl", "buck_module")

buck_module(
    name = "rules",
    srcs = glob(["*.java"]),
    provided_deps = [
        "//src/com/facebook/buck/event:interfaces",
        ...
        "//third-party/java/infer-annotations:infer-annotations",
    ],
    tests = [
        "//test/com/facebook/buck/zip/rules:rules",
    ],
    visibility = [
        "//test/com/facebook/buck/ide/intellij:intellij",
        "//test/com/facebook/buck/zip/rules:rules",
    ],
)
```

This target uses `provided_deps` to specify dependencies which should be excluded from the module
jar. You need to use `deps` for dependencies that must be packaged in a jar.

If a module has corresponding external resources (files not included in a jar), they can be listed
in `module_resources` attribute. See Python module (in `src/com/facebook/buck/features/python`) for
example.

## 3. Provide information about a module

Use `BuckModule` annotation to specify module's id and its dependencies.

`ZipRulesModule` as an example:

```
package com.facebook.buck.zip.rules;

import com.facebook.buck.module.BuckModule;
import com.facebook.buck.zip.bundler.ZipBundlerModule;

/** A modules that provides `zip_rule` build rule. */
@BuckModule(
  dependencies = {
    ZipBundlerModule.class,
  }
)
public class ZipRulesModule {}
```

Dependencies are specified as a list of classes that have BuckModule annotation with the description
of the modules.

## 4. Add new module to Buck build of Buck

Update the `BUCK_MODULES` list in `programs/BUCK`:

```
BUCK_MODULES = {
    "zipbundler": "//src/com/facebook/buck/zip/bundler:bundler",
    "ziprules": "//src/com/facebook/buck/zip/rules:rules",
}
```

If a module has external resources update `BUCK_MODULES_WITH_RESOURCES` to include the module's
name.

## 5. Add new module to ant build of Buck

### 5.1. Add new target in build.xml

There is no automatic solution for ant so adding a new module requires creating a new target in
`build.xml`:

```
<target name="build-module-ziprules" depends="compile, build-module-zipbundler">
  <build-buck-module-jar module-name="ziprules">
    <module-javac-params>
      <include name="com/facebook/buck/zip/rules/**/*.java" />
    </module-javac-params>
    <additional-classpath-entries>
      <include name="zipbundler.jar"/>
    </additional-classpath-entries>
  </build-buck-module-jar>
</target>
```

Use `additional-classpath-entries` to list dependencies using the name of the jars with modules.

Use `module-javac-params` to specify the location of source code for the new module.

Use `copy-buck-module-external-resources` to add external resources.

### 5.2 Exclude module's source code from `compile` target

`compile` target should be updated to exclude the source code of the new module:

```
<exclude name="com/facebook/buck/zip/rules/**" />
```

Code in `src/com/facebook/buck/features` is excluded from the main binary and can be used to keep
modules.

### 5.3 Add newly created target to the dependencies of the `build-modules` target

This is required in order to build the module with `ant` or `ant default`.
