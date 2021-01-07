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

import static java.util.stream.Collectors.*;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
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
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class HaskellIdeRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey HaskellSources srcs;
  @AddToRuleKey Collection<String> compilerFlags;
  @AddToRuleKey ArchiveContents archiveContents;
  @AddToRuleKey ImmutableList<SourcePath> extraScriptTemplates;
  @AddToRuleKey Linker.LinkableDepType linkStyle;
  @AddToRuleKey ImmutableSet<SourcePath> pkgDirs;
  @AddToRuleKey ImmutableSet<HaskellPackageInfo> exposedPackages;

  @AddToRuleKey(stringify = true)
  Path scriptTemplate;

  @AddToRuleKey(stringify = true)
  Path ghciBinutils;

  @AddToRuleKey(stringify = true)
  Path ghciCxx;

  @AddToRuleKey(stringify = true)
  Path ghciCc;

  @AddToRuleKey(stringify = true)
  Path ghciCpp;

  // Don't add to rulekey - expensive to compute and should be unnecessary
  ImmutableMap<BuildTarget, HaskellPackageInfo> ideProjects;

  private HaskellIdeRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      HaskellSources srcs,
      ImmutableMap<BuildTarget, HaskellPackageInfo> ideProjects,
      Collection<String> compilerFlags,
      ImmutableSet<SourcePath> pkgDirs,
      ImmutableSet<HaskellPackageInfo> exposedPackages,
      ImmutableList<SourcePath> extraScriptTemplates,
      Linker.LinkableDepType linkStyle,
      ArchiveContents archiveContents,
      Path scriptTemplate,
      Path ghciBinutils,
      Path ghciCxx,
      Path ghciCc,
      Path ghciCpp) {
    super(buildTarget, projectFilesystem, params);
    this.srcs = srcs;
    this.compilerFlags = compilerFlags;
    this.pkgDirs = pkgDirs;
    this.exposedPackages = exposedPackages;
    this.archiveContents = archiveContents;
    this.ghciBinutils = ghciBinutils;
    this.scriptTemplate = scriptTemplate;
    this.linkStyle = linkStyle;
    this.ghciCxx = ghciCxx;
    this.ghciCc = ghciCc;
    this.ghciCpp = ghciCpp;
    this.ideProjects = ideProjects;
    this.extraScriptTemplates = extraScriptTemplates;
  }

  public static HaskellIdeRule from(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      HaskellSources srcs,
      Collection<BuildTarget> ideTargets,
      Collection<String> compilerFlags,
      ImmutableMap<BuildRule, HaskellPackage> haskellPackages,
      ImmutableMap<BuildRule, HaskellPackage> prebuiltHaskellPackages,
      HaskellPlatform platform,
      Linker.LinkableDepType linkStyle,
      ImmutableList<SourcePath> extraScriptTemplates) {

    ImmutableSet.Builder<BuildRule> extraDeps = ImmutableSet.builder();

    for (HaskellPackage pkg : haskellPackages.values()) {
      extraDeps.addAll(pkg.getDeps(ruleFinder)::iterator);
    }

    for (HaskellPackage pkg : prebuiltHaskellPackages.values()) {
      extraDeps.addAll(pkg.getDeps(ruleFinder)::iterator);
    }

    Supplier<ImmutableSortedSet<BuildRule>> declaredDeps =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(srcs.getDeps(ruleFinder))
                    .build());
    BuildRuleParams paramsWithExtraDeps =
        params.withDeclaredDeps(declaredDeps).copyAppendingExtraDeps(extraDeps.build());

    ImmutableMap<BuildTarget, HaskellPackageInfo> ideProjects =
        ideTargets.stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    t -> t, t -> HaskellLibraryDescription.getPackageInfo(platform, t)));

    ImmutableSet<SourcePath> pkgDirs =
        Stream.concat(prebuiltHaskellPackages.values().stream(), haskellPackages.values().stream())
            .map(pkg -> pkg.getPackageDb())
            .collect(ImmutableSet.toImmutableSet());

    Set<BuildRule> ruleDeps = params.getBuildDeps();

    ImmutableSet<HaskellPackageInfo> exposedPackages =
        Stream.concat(
                haskellPackages.entrySet().stream(), prebuiltHaskellPackages.entrySet().stream())
            .filter(e -> ruleDeps.contains(e.getKey()))
            .map(e -> e.getValue().getInfo())
            .collect(ImmutableSet.toImmutableSet());

    return new HaskellIdeRule(
        buildTarget,
        projectFilesystem,
        paramsWithExtraDeps,
        srcs,
        ideProjects,
        compilerFlags,
        pkgDirs,
        exposedPackages,
        extraScriptTemplates,
        linkStyle,
        platform.getArchiveContents(),
        platform.getIdeScriptTemplate().get(),
        platform.getGhciBinutils().get(),
        platform.getGhciCxx().get(),
        platform.getGhciCc().get(),
        platform.getGhciCpp().get());
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
    RelPath dir = getOutputDir();

    ImmutableList.Builder<String> compilerFlagsBuilder = ImmutableList.builder();
    compilerFlagsBuilder.addAll(compilerFlags);
    compilerFlagsBuilder.addAll(HaskellDescriptionUtils.PIC_FLAGS);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), dir)));

    ImmutableSet.Builder<Path> srcDirs = ImmutableSet.builder();
    ImmutableSet.Builder<RelPath> srcPaths = ImmutableSet.builder();
    for (Map.Entry<HaskellSourceModule, SourcePath> e : srcs.getModuleMap().entrySet()) {
      RelPath srcPath = resolver.getCellUnsafeRelPath(e.getValue());
      srcPaths.add(srcPath);
      String moduleName = e.getKey().getModuleName();
      Path importDir = getImportDirForModule(moduleName, srcPath.getPath());
      srcDirs.add(importDir);
    }

    String importDirs =
        srcDirs.build().stream().map(s -> makeRelativeToParent(dir, s)).collect(joining(" "));
    String packageDbs =
        pkgDirs.stream().map(p -> makeRelativeToParent(resolver, dir, p)).collect(joining(" "));
    String ignorePackages =
        ideProjects.values().stream()
            .map(p -> String.format("%s-%s", p.getName(), p.getVersion()))
            .collect(joining(" "));
    String exposePackages =
        exposedPackages.stream()
            .map(p -> String.format("%s-%s", p.getName(), p.getVersion()))
            .collect(joining(" "));
    Collection<String> compilerFlagsFinal = compilerFlagsBuilder.build();
    String compilerFlags =
        compilerFlagsFinal.stream().map(x -> String.format("\"%s\"", x)).collect(joining(" "));

    buildableContext.recordArtifact(dir.getPath());

    String name = getBuildTarget().getShortName();
    ImmutableMap.Builder<String, String> templateArgs = ImmutableMap.builder();
    try {
      templateArgs.put("name", name);
      templateArgs.put("link_style", linkStyle.toString());
      templateArgs.put("exposed_packages", exposePackages);
      templateArgs.put("ignored_packages", ignorePackages);
      templateArgs.put("package_dbs", packageDbs);
      templateArgs.put("compiler_flags", compilerFlags);
      templateArgs.put("import_dirs", importDirs);
      templateArgs.put(
          "srcs",
          srcPaths.build().stream()
              .map(s -> makeRelativeToParent(dir, s).toString())
              .collect(joining(" ")));
      templateArgs.put("binutils_path", ghciBinutils.toRealPath().toString());
      templateArgs.put("cxx_path", ghciCxx.toRealPath().toString());
      templateArgs.put("cc_path", ghciCc.toRealPath().toString());
      templateArgs.put("cpp_path", ghciCpp.toRealPath().toString());
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }

    Path script = scriptPath();
    steps.add(
        new StringTemplateStep(
            scriptTemplate, getProjectFilesystem(), script, templateArgs.build()));
    for (SourcePath s : extraScriptTemplates) {
      AbsPath templateAbsPath = resolver.getAbsolutePath(s);
      Path extraScript = dir.resolve(templateAbsPath.getFileName());
      steps.add(
          new StringTemplateStep(
              templateAbsPath.getPath(),
              getProjectFilesystem(),
              extraScript,
              templateArgs.build()));
    }
    return steps.build();
  }

  private Path scriptPath() {
    return getOutputDir().resolve("flags.sh");
  }

  private String makeRelativeToParent(RelPath parent, Path path) {
    if (path.isAbsolute()) return path.toString();
    return makeRelativeToParent(parent, RelPath.of(path));
  }

  private String makeRelativeToParent(RelPath parent, RelPath path) {
    return "${DIR}/" + parent.relativize(path).toString();
  }

  private String makeRelativeToParent(
      SourcePathResolverAdapter resolver, RelPath parent, SourcePath spath) {
    Path path = resolver.getCellUnsafeRelPath(spath).getPath();
    return makeRelativeToParent(parent, RelPath.of(path));
  }

  @Override
  public boolean isCacheable() {
    return true;
  }

  private Path getImportDirForModule(String moduleName, Path modulePath) {
    long moduleComponents = moduleName.codePoints().filter(ch -> ch == '.').count();
    return modulePath.subpath(0, modulePath.getNameCount() - 1 - (int) moduleComponents);
  }
}
