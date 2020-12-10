/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiConsumer;

public class HaskellIdeRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey HaskellSources srcs;

  @AddToRuleKey ImmutableList<String> compilerFlags;

  @AddToRuleKey ImmutableSet<HaskellPackage> firstOrderHaskellPackages;

  @AddToRuleKey ImmutableSet<HaskellPackage> haskellPackages;

  @AddToRuleKey ImmutableSet<HaskellPackage> prebuiltHaskellPackages;

  @AddToRuleKey ArchiveContents archiveContents;

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

  @AddToRuleKey(stringify = true)
  Path ghciPackager;

  private HaskellIdeRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      HaskellSources srcs,
      ImmutableList<String> compilerFlags,
      ImmutableSet<HaskellPackage> firstOrderHaskellPackages,
      ImmutableSet<HaskellPackage> haskellPackages,
      ImmutableSet<HaskellPackage> prebuiltHaskellPackages,
      ArchiveContents archiveContents,
      Path ghciScriptTemplate,
      Path ghciBinutils,
      Path ghciGhc,
      Path ghciLib,
      Path ghciCxx,
      Path ghciCc,
      Path ghciCpp,
      Path ghciPackager) {
    super(buildTarget, projectFilesystem, params);
    this.srcs = srcs;
    this.compilerFlags = compilerFlags;
    this.firstOrderHaskellPackages = firstOrderHaskellPackages;
    this.haskellPackages = haskellPackages;
    this.prebuiltHaskellPackages = prebuiltHaskellPackages;
    this.archiveContents = archiveContents;
    this.ghciScriptTemplate = ghciScriptTemplate;
    this.ghciBinutils = ghciBinutils;
    this.ghciGhc = ghciGhc;
    this.ghciLib = ghciLib;
    this.ghciCxx = ghciCxx;
    this.ghciCc = ghciCc;
    this.ghciCpp = ghciCpp;
    this.ghciPackager = ghciPackager;
  }

  public static HaskellIdeRule from(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      HaskellSources srcs,
      ImmutableList<String> compilerFlags,
      ImmutableSet<HaskellPackage> firstOrderHaskellPackages,
      ImmutableSet<HaskellPackage> haskellPackages,
      ImmutableSet<HaskellPackage> prebuiltHaskellPackages,
      HaskellPlatform platform) {

    ImmutableSet.Builder<BuildRule> extraDeps = ImmutableSet.builder();

    for (HaskellPackage pkg : haskellPackages) {
      extraDeps.addAll(pkg.getDeps(ruleFinder)::iterator);
    }

    for (HaskellPackage pkg : prebuiltHaskellPackages) {
      extraDeps.addAll(pkg.getDeps(ruleFinder)::iterator);
    }

    return new HaskellIdeRule(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(extraDeps.build()),
        srcs,
        compilerFlags,
        firstOrderHaskellPackages,
        haskellPackages,
        prebuiltHaskellPackages,
        platform.getArchiveContents(),
        platform.getGhciScriptTemplate().get(),
        platform.getGhciBinutils().get(),
        platform.getGhciGhc().get(),
        platform.getGhciLib().get(),
        platform.getGhciCxx().get(),
        platform.getGhciCc().get(),
        platform.getGhciCpp().get(),
        platform.getGhciPackager().get());
  }

  private RelPath getOutputDir() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem().getBuckPaths(), getBuildTarget(), "%s");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getOutputDir());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    SourcePathResolverAdapter resolver = context.getSourcePathResolver();

    String name = getBuildTarget().getShortName();
    RelPath dir = getOutputDir();
    Path binDir = dir.resolve(name + ".bin");
    Path packagesDir = dir.resolve(name + ".packages");

    ImmutableList.Builder<String> compilerFlagsBuilder = ImmutableList.builder();
    compilerFlagsBuilder.addAll(compilerFlags);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), dir)));
    for (Path subdir : ImmutableList.of(binDir, packagesDir)) {
      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), subdir)));
    }

    ImmutableSet.Builder<String> pkgdirs = ImmutableSet.builder();
    for (HaskellPackage pkg : prebuiltHaskellPackages) {
      try {
        pkgdirs.add(
            resolver.getCellUnsafeRelPath(pkg.getPackageDb()).getPath().toRealPath().toString());
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    ImmutableMap.Builder<Path, Path> packageLinks = ImmutableMap.builder();
    BiConsumer<Path, Path> putLink =
        (destination, source) ->
            packageLinks.put(
                destination, packagesDir.resolve(destination.getParent()).relativize(source));
    for (HaskellPackage pkg : haskellPackages) {
      Path pkgdir = Paths.get(pkg.getInfo().getName());

      RelPath pkgDbSrc = resolver.getCellUnsafeRelPath(pkg.getPackageDb());
      Path pkgDbLink = pkgdir.resolve(pkgDbSrc.getFileName());
      putLink.accept(pkgDbLink, pkgDbSrc.getPath());
      pkgdirs.add("${DIR}/" + dir.relativize(packagesDir.resolve(pkgDbLink)));

      ImmutableSet.Builder<SourcePath> artifacts = ImmutableSet.builder();
      artifacts.addAll(pkg.getLibraries());

      if (archiveContents == ArchiveContents.THIN) {
        // this is required because the .a files above are thin archives,
        // they merely point to the .o files via a relative path.
        artifacts.addAll(pkg.getObjects());
      }

      artifacts.addAll(pkg.getInterfaces());

      for (SourcePath artifact : artifacts.build()) {
        RelPath source = resolver.getCellUnsafeRelPath(artifact);
        Path destination =
            pkgdir.resolve(
                source.subpath(source.getNameCount() - 2, source.getNameCount()).getPath());
        putLink.accept(destination, source.getPath());
      }
    }

    ImmutableSet.Builder<String> exposedPkgs = ImmutableSet.builder();
    for (HaskellPackage pkg : firstOrderHaskellPackages) {
      exposedPkgs.add(String.format("%s-%s", pkg.getInfo().getName(), pkg.getInfo().getVersion()));
    }

    ImmutableList.Builder<String> srcpaths = ImmutableList.builder();
    for (SourcePath sp : srcs.getSourcePaths()) {
      srcpaths.add(resolver.getCellUnsafeRelPath(sp).toString());
    }

    String ghcPath;
    try {
      ghcPath = ghciGhc.toRealPath().toString();
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

    compilerFlagsBuilder.addAll(HaskellDescriptionUtils.PIC_FLAGS);

    String ghc = ghcPath;
    ImmutableMap.Builder<String, String> templateArgs = ImmutableMap.builder();
    try {
      templateArgs.put("name", name);
      templateArgs.put("exposed_packages", exposed);
      templateArgs.put("package_dbs", pkgdbs);
      templateArgs.put("compiler_flags", Joiner.on(' ').join(compilerFlagsBuilder.build()));
      templateArgs.put("srcs", Joiner.on(' ').join(srcpaths.build()));
      templateArgs.put("binutils_path", ghciBinutils.toRealPath().toString());
      // ghc_path points to the ghc tool for this binary
      templateArgs.put("ghc_path", ghciGhc.toRealPath().toString());
      templateArgs.put("user_ghci_path", ghc);
      templateArgs.put("cxx_path", ghciCxx.toRealPath().toString());
      templateArgs.put("cc_path", ghciCc.toRealPath().toString());
      templateArgs.put("cpp_path", ghciCpp.toRealPath().toString());
      templateArgs.put("ghc_pkg_path", ghciPackager.toRealPath().toString());
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }

    Path script = scriptPath();
    steps.add(
        new StringTemplateStep(
            ghciScriptTemplate, getProjectFilesystem(), script, templateArgs.build()));

    buildableContext.recordArtifact(dir.getPath());

    return steps.build();
  }

  private Path scriptPath() {
    return getOutputDir().resolve(getBuildTarget().getShortName());
  }

  @Override
  public boolean isCacheable() {
    return true;
  }
}
