/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.features.haskell;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class HaskellPackageRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final Tool ghcPkg;

  private final HaskellVersion haskellVersion;

  @AddToRuleKey Linker.LinkableDepType depType;

  @AddToRuleKey private final HaskellPackageInfo packageInfo;

  @AddToRuleKey private final ImmutableSortedMap<String, HaskellPackage> depPackages;

  @AddToRuleKey private final ImmutableSortedSet<String> modules;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> libraries;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> interfaces;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> objects;

  public HaskellPackageRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Tool ghcPkg,
      HaskellVersion haskellVersion,
      Linker.LinkableDepType depType,
      HaskellPackageInfo packageInfo,
      ImmutableSortedMap<String, HaskellPackage> depPackages,
      ImmutableSortedSet<String> modules,
      ImmutableSortedSet<SourcePath> libraries,
      ImmutableSortedSet<SourcePath> interfaces,
      ImmutableSortedSet<SourcePath> objects) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.ghcPkg = ghcPkg;
    this.haskellVersion = haskellVersion;
    this.depType = depType;
    this.packageInfo = packageInfo;
    this.depPackages = depPackages;
    this.modules = modules;
    this.libraries = libraries;
    this.interfaces = interfaces;
    this.objects = objects;
  }

  public static HaskellPackageRule from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      SourcePathRuleFinder ruleFinder,
      Tool ghcPkg,
      HaskellVersion haskellVersion,
      Linker.LinkableDepType depType,
      HaskellPackageInfo packageInfo,
      ImmutableSortedMap<String, HaskellPackage> depPackages,
      ImmutableSortedSet<String> modules,
      ImmutableSortedSet<SourcePath> libraries,
      ImmutableSortedSet<SourcePath> interfaces,
      ImmutableSortedSet<SourcePath> objects) {
    Supplier<ImmutableSortedSet<BuildRule>> declaredDeps =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(BuildableSupport.getDepsCollection(ghcPkg, ruleFinder))
                    .addAll(
                        depPackages
                            .values()
                            .stream()
                            .flatMap(pkg -> pkg.getDeps(ruleFinder))
                            .iterator())
                    .addAll(
                        ruleFinder.filterBuildRuleInputs(Iterables.concat(libraries, interfaces)))
                    .build());
    return new HaskellPackageRule(
        target,
        projectFilesystem,
        baseParams.withDeclaredDeps(declaredDeps).withoutExtraDeps(),
        ghcPkg,
        haskellVersion,
        depType,
        packageInfo,
        depPackages,
        modules,
        libraries,
        interfaces,
        objects);
  }

  private Path getPackageDb() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  private WriteFileStep getWriteRegistrationFileStep(
      SourcePathResolver resolver, Path registrationFile, Path packageDb) {
    Map<String, String> entries = new LinkedHashMap<>();

    entries.put("name", packageInfo.getName());
    entries.put("version", packageInfo.getVersion());
    entries.put("id", packageInfo.getIdentifier());

    if (haskellVersion.getMajorVersion() >= 8) {
      entries.put("key", packageInfo.getIdentifier());
    }

    entries.put("exposed", "True");
    entries.put("exposed-modules", Joiner.on(' ').join(modules));

    Path pkgRoot = getProjectFilesystem().getPath("${pkgroot}");

    if (!modules.isEmpty()) {
      Set<String> importDirs = new LinkedHashSet<>();
      for (SourcePath interfaceDir : interfaces) {
        Path relInterfaceDir =
            pkgRoot.resolve(
                packageDb.getParent().relativize(resolver.getRelativePath(interfaceDir)));
        importDirs.add('"' + relInterfaceDir.toString() + '"');
      }
      entries.put("import-dirs", Joiner.on(", ").join(importDirs));
    }

    Set<String> libDirs = new LinkedHashSet<>();
    Set<String> libs = new LinkedHashSet<>();
    for (SourcePath library : libraries) {
      Path relLibPath =
          pkgRoot.resolve(packageDb.getParent().relativize(resolver.getRelativePath(library)));
      libDirs.add('"' + relLibPath.getParent().toString() + '"');

      String libName = MorePaths.stripPathPrefixAndExtension(relLibPath.getFileName(), "lib");
      libs.add(libName.replaceAll("_p$", ""));
    }
    entries.put("library-dirs", Joiner.on(", ").join(libDirs));

    if (Linker.LinkableDepType.STATIC == depType) {
      entries.put("hs-libraries", Joiner.on(", ").join(libs));
    } else {
      entries.put("extra-libraries", Joiner.on(", ").join(libs));
    }

    entries.put("depends", Joiner.on(", ").join(depPackages.keySet()));

    return new WriteFileStep(
        getProjectFilesystem(),
        entries
            .entrySet()
            .stream()
            .map(input -> input.getKey() + ": " + input.getValue())
            .collect(Collectors.joining(System.lineSeparator())),
        registrationFile,
        /* executable */ false);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Setup the scratch dir.
    Path scratchDir =
        BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s");

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), scratchDir)));

    // Setup the package DB directory.
    Path packageDb = getPackageDb();
    steps.add(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), packageDb))
            .withRecursive(true));
    buildableContext.recordArtifact(packageDb);

    // Create the registration file.
    Path registrationFile = scratchDir.resolve("registration-file");
    steps.add(
        getWriteRegistrationFileStep(context.getSourcePathResolver(), registrationFile, packageDb));

    // Initialize the package DB.
    steps.add(
        new GhcPkgStep(
            context.getSourcePathResolver(),
            ImmutableList.of("init", packageDb.toString()),
            ImmutableMap.of()));

    // Build the the package DB.
    ImmutableList.Builder<String> ghcPkgCmdBuilder = ImmutableList.builder();
    ghcPkgCmdBuilder.add("-v0", "register", "--package-conf=" + packageDb, "--no-expand-pkgroot");
    // Older versions of `ghc-pkg` appear to fail finding the interface files which are being
    // exported by this package, so ignore these failure explicitly.
    if (haskellVersion.getMajorVersion() < 8) {
      ghcPkgCmdBuilder.add("--force-files");
    }
    ghcPkgCmdBuilder.add(registrationFile.toString());
    ImmutableList<String> ghcPkgCmd = ghcPkgCmdBuilder.build();
    steps.add(
        new GhcPkgStep(
            context.getSourcePathResolver(),
            ghcPkgCmd,
            ImmutableMap.of(
                "GHC_PACKAGE_PATH",
                depPackages
                    .values()
                    .stream()
                    .map(
                        input ->
                            context
                                .getSourcePathResolver()
                                .getAbsolutePath(input.getPackageDb())
                                .toString())
                    // Different packages might have the same underlying package DB and specifying
                    // the same package DB multiple times to `ghc-pkg` will cause additional
                    // processing which can make `ghc-pkg` really slow.  So, dedup the package DBs
                    // before passing them into `ghc-pkg`.
                    .distinct()
                    .collect(Collectors.joining(":")))));

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPackageDb());
  }

  public HaskellPackage getPackage() {
    return HaskellPackage.builder()
        .setInfo(packageInfo)
        .setPackageDb(ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPackageDb()))
        .addAllLibraries(libraries)
        .addAllInterfaces(interfaces)
        .addAllObjects(objects)
        .build();
  }

  private class GhcPkgStep extends ShellStep {

    private final SourcePathResolver resolver;
    private final ImmutableList<String> args;
    private final ImmutableMap<String, String> env;

    public GhcPkgStep(
        SourcePathResolver resolver, ImmutableList<String> args, ImmutableMap<String, String> env) {
      super(getProjectFilesystem().getRootPath());
      this.resolver = resolver;
      this.args = args;
      this.env = env;
    }

    @Override
    protected boolean shouldPrintStderr(Verbosity verbosity) {
      return !verbosity.isSilent();
    }

    @Override
    protected final ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      return ImmutableList.<String>builder()
          .addAll(ghcPkg.getCommandPrefix(resolver))
          .addAll(args)
          .build();
    }

    @Override
    public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
      return ImmutableMap.<String, String>builder()
          .putAll(super.getEnvironmentVariables(context))
          .putAll(ghcPkg.getEnvironment(resolver))
          .putAll(env)
          .build();
    }

    @Override
    public final String getShortName() {
      return "ghc-pkg";
    }
  }
}
