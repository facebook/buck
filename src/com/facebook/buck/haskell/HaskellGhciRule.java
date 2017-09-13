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
package com.facebook.buck.haskell;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.NonHashableSourcePathContainer;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MakeExecutableStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class HaskellGhciRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  BuildRuleResolver buildRuleResolver;

  @AddToRuleKey HaskellSources srcs;

  @AddToRuleKey ImmutableList<String> compilerFlags;

  @AddToRuleKey Optional<BuildTarget> ghciBinDep;

  @AddToRuleKey Optional<SourcePath> ghciInit;

  @AddToRuleKey BuildRule omnibusSharedObject;

  ImmutableSortedMap<String, SourcePath> solibs;

  @AddToRuleKey ImmutableSet<HaskellPackage> firstOrderHaskellPackages;

  @AddToRuleKey ImmutableSet<HaskellPackage> haskellPackages;

  @AddToRuleKey ImmutableSet<HaskellPackage> prebuiltHaskellPackages;

  @AddToRuleKey boolean enableProfiling;

  @AddToRuleKey(stringify = true)
  Path ghciScriptTemplate;

  @AddToRuleKey(stringify = true)
  Path ghciBinutils;

  @AddToRuleKey(stringify = true)
  Path ghciGhc;

  @AddToRuleKey(stringify = true)
  Path ghciLib;

  @AddToRuleKey(stringify = true)
  Path ghciCxx;

  @AddToRuleKey(stringify = true)
  Path ghciCc;

  @AddToRuleKey(stringify = true)
  Path ghciCpp;

  private HaskellGhciRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      HaskellSources srcs,
      ImmutableList<String> compilerFlags,
      Optional<BuildTarget> ghciBinDep,
      Optional<SourcePath> ghciInit,
      BuildRule omnibusSharedObject,
      ImmutableSortedMap<String, SourcePath> solibs,
      ImmutableSet<HaskellPackage> firstOrderHaskellPackages,
      ImmutableSet<HaskellPackage> haskellPackages,
      ImmutableSet<HaskellPackage> prebuiltHaskellPackages,
      boolean enableProfiling,
      Path ghciScriptTemplate,
      Path ghciBinutils,
      Path ghciGhc,
      Path ghciLib,
      Path ghciCxx,
      Path ghciCc,
      Path ghciCpp) {
    super(buildTarget, projectFilesystem, params);
    this.buildRuleResolver = buildRuleResolver;
    this.srcs = srcs;
    this.compilerFlags = compilerFlags;
    this.ghciBinDep = ghciBinDep;
    this.ghciInit = ghciInit;
    this.omnibusSharedObject = omnibusSharedObject;
    this.solibs = solibs;
    this.firstOrderHaskellPackages = firstOrderHaskellPackages;
    this.haskellPackages = haskellPackages;
    this.prebuiltHaskellPackages = prebuiltHaskellPackages;
    this.enableProfiling = enableProfiling;
    this.ghciScriptTemplate = ghciScriptTemplate;
    this.ghciBinutils = ghciBinutils;
    this.ghciGhc = ghciGhc;
    this.ghciLib = ghciLib;
    this.ghciCxx = ghciCxx;
    this.ghciCc = ghciCc;
    this.ghciCpp = ghciCpp;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("links", solibsForRuleKey());
  }

  private ImmutableSortedMap<String, NonHashableSourcePathContainer> solibsForRuleKey() {
    ImmutableSortedMap.Builder<String, NonHashableSourcePathContainer> solibMap =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<String, SourcePath> entry : solibs.entrySet()) {
      solibMap.put(entry.getKey(), new NonHashableSourcePathContainer(entry.getValue()));
    }

    return solibMap.build();
  }

  public static HaskellGhciRule from(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      HaskellSources srcs,
      ImmutableList<String> compilerFlags,
      Optional<BuildTarget> ghciBinDep,
      Optional<SourcePath> ghciInit,
      BuildRule omnibusSharedObject,
      ImmutableSortedMap<String, SourcePath> solibs,
      ImmutableSet<HaskellPackage> firstOrderHaskellPackages,
      ImmutableSet<HaskellPackage> haskellPackages,
      ImmutableSet<HaskellPackage> prebuiltHaskellPackages,
      boolean enableProfiling,
      Path ghciScriptTemplate,
      Path ghciBinutils,
      Path ghciGhc,
      Path ghciLib,
      Path ghciCxx,
      Path ghciCc,
      Path ghciCpp) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    ImmutableSet.Builder<BuildRule> extraDeps = ImmutableSet.builder();

    extraDeps.add(omnibusSharedObject);

    for (HaskellPackage pkg : haskellPackages) {
      extraDeps.addAll(pkg.getDeps(ruleFinder)::iterator);
    }

    for (HaskellPackage pkg : prebuiltHaskellPackages) {
      extraDeps.addAll(pkg.getDeps(ruleFinder)::iterator);
    }

    if (ghciBinDep.isPresent()) {
      extraDeps.add(resolver.getRule(ghciBinDep.get()));
    }

    extraDeps.addAll(ruleFinder.filterBuildRuleInputs(solibs.values()));

    return new HaskellGhciRule(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(extraDeps.build()),
        resolver,
        srcs,
        compilerFlags,
        ghciBinDep,
        ghciInit,
        omnibusSharedObject,
        solibs,
        firstOrderHaskellPackages,
        haskellPackages,
        prebuiltHaskellPackages,
        enableProfiling,
        ghciScriptTemplate,
        ghciBinutils,
        ghciGhc,
        ghciLib,
        ghciCxx,
        ghciCc,
        ghciCpp);
  }

  private Path getOutputDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getOutputDir());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    SourcePathResolver resolver = context.getSourcePathResolver();

    String name = getBuildTarget().getShortName();
    Path dir = getOutputDir();
    Path so = resolver.getRelativePath(omnibusSharedObject.getSourcePathToOutput());
    Path packagesDir = dir.resolve(name + ".packages");
    Path symlinkDir = dir.resolve(name + ".so-symlinks");

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), dir)));
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), symlinkDir)));
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), packagesDir)));

    steps.add(CopyStep.forFile(getProjectFilesystem(), so, dir.resolve(so.getFileName())));

    try {
      for (Map.Entry<String, SourcePath> ent : solibs.entrySet()) {
        Path src = resolver.getRelativePath(ent.getValue()).toRealPath();
        Path dest = symlinkDir.resolve(ent.getKey());
        steps.add(
            SymlinkFileStep.builder()
                .setFilesystem(getProjectFilesystem())
                .setExistingFile(src)
                .setDesiredLink(dest)
                .build());
      }
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }

    ImmutableSet.Builder<String> pkgdirs = ImmutableSet.builder();
    for (HaskellPackage pkg : prebuiltHaskellPackages) {
      try {
        pkgdirs.add(resolver.getRelativePath(pkg.getPackageDb()).toRealPath().toString());
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    for (HaskellPackage pkg : haskellPackages) {
      String pkgname = pkg.getInfo().getName();
      Path pkgdir = packagesDir.resolve(pkgname);
      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), pkgdir)));

      Path pkgDbSrc = resolver.getRelativePath(pkg.getPackageDb());
      steps.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              pkgDbSrc,
              pkgdir,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));

      ImmutableSet.Builder<Path> artifacts = ImmutableSet.builder();
      for (SourcePath lib : pkg.getLibraries()) {
        artifacts.add(resolver.getRelativePath(lib).getParent());
      }

      // this is required because the .a files above are thin archives,
      // they merely point to the .o files via a relative path.
      for (SourcePath obj : pkg.getObjects()) {
        artifacts.add(resolver.getRelativePath(obj).getParent());
      }

      for (SourcePath iface : pkg.getInterfaces()) {
        artifacts.add(resolver.getRelativePath(iface).getParent());
      }

      for (Path artifact : artifacts.build()) {
        steps.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                artifact,
                pkgdir,
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      }

      pkgdirs.add("${DIR}/" + dir.relativize(pkgdir.resolve(pkgDbSrc.getFileName())).toString());
    }

    ImmutableSet.Builder<String> exposedPkgs = ImmutableSet.builder();
    for (HaskellPackage pkg : firstOrderHaskellPackages) {
      exposedPkgs.add(String.format("%s-%s", pkg.getInfo().getName(), pkg.getInfo().getVersion()));
    }

    StringBuilder startGhciContents = new StringBuilder();
    startGhciContents.append(":set ");
    startGhciContents.append(
        Joiner.on(' ')
            .join(
                ImmutableList.<String>builder()
                    .addAll(
                        MoreIterables.zipAndConcat(
                            Iterables.cycle("-package"), exposedPkgs.build()))
                    .build()));

    if (ghciInit.isPresent()) {
      try {
        startGhciContents.append('\n');
        List<String> lines =
            Files.readAllLines(resolver.getRelativePath(ghciInit.get()), StandardCharsets.UTF_8);
        startGhciContents.append(Joiner.on('\n').join(lines));
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    Path startGhci = dir.resolve("start.ghci");
    steps.add(
        new WriteFileStep(
            getProjectFilesystem(),
            startGhciContents.toString(),
            startGhci,
            /* executable */ false));

    ImmutableList.Builder<String> srcpaths = ImmutableList.builder();
    for (SourcePath sp : srcs.getSourcePaths()) {
      srcpaths.add(resolver.getRelativePath(sp).toString());
    }

    String ghcPath = null;
    try {
      if (ghciBinDep.isPresent()) {

        Path binDir = dir.resolve(name + ".bin");
        Path bin = binDir.resolve("ghci");
        BuildRule rule = buildRuleResolver.getRule(ghciBinDep.get());
        SourcePath sp = rule.getSourcePathToOutput();

        steps.addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), binDir)));

        steps.add(CopyStep.forFile(getProjectFilesystem(), resolver.getRelativePath(sp), bin));

        ghcPath = "${DIR}/" + dir.relativize(bin).toString() + " -B" + ghciLib.toRealPath();
      } else {
        ghcPath = ghciGhc.toRealPath().toString();
      }
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }

    String pkgdbs =
        Joiner.on(' ')
            .join(
                ImmutableList.<String>builder()
                    .addAll(
                        MoreIterables.zipAndConcat(Iterables.cycle("-package-db"), pkgdirs.build()))
                    .build());
    String exposed =
        Joiner.on(' ')
            .join(
                ImmutableList.<String>builder()
                    .addAll(
                        MoreIterables.zipAndConcat(
                            Iterables.cycle("-expose-package"), exposedPkgs.build()))
                    .build());

    if (enableProfiling) {
      compilerFlags =
          ImmutableList.copyOf(Iterables.concat(compilerFlags, HaskellDescriptionUtils.PROF_FLAGS));
    }

    String ghc = ghcPath;
    ImmutableMap.Builder<String, String> templateArgs = ImmutableMap.builder();
    try {
      templateArgs.put("name", name);
      templateArgs.put("start_ghci", dir.relativize(startGhci).toString());
      templateArgs.put("ld_library_path", dir.relativize(symlinkDir).toString());
      templateArgs.put("exposed_packages", exposed);
      templateArgs.put("package_dbs", pkgdbs);
      templateArgs.put("compiler_flags", Joiner.on(' ').join(compilerFlags));
      templateArgs.put("srcs", Joiner.on(' ').join(srcpaths.build()));
      templateArgs.put("squashed_so", dir.relativize(dir.resolve(so.getFileName())).toString());
      templateArgs.put("binutils_path", ghciBinutils.toRealPath().toString());
      templateArgs.put("ghc_path", ghc);
      templateArgs.put("cxx_path", ghciCxx.toRealPath().toString());
      templateArgs.put("cc_path", ghciCc.toRealPath().toString());
      templateArgs.put("cpp_path", ghciCpp.toRealPath().toString());
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }

    Path script = dir.resolve(name);
    steps.add(
        new StringTemplateStep(
            ghciScriptTemplate, getProjectFilesystem(), script, templateArgs.build()));
    steps.add(new MakeExecutableStep(getProjectFilesystem(), script));

    buildableContext.recordArtifact(dir);

    return steps.build();
  }
}
