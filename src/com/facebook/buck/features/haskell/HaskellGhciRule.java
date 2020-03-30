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
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MakeExecutableStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import org.stringtemplate.v4.ST;

public class HaskellGhciRule extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule {

  @AddToRuleKey HaskellSources srcs;

  @AddToRuleKey ImmutableList<String> compilerFlags;

  @AddToRuleKey Optional<SourcePath> ghciBinDep;

  @AddToRuleKey Optional<SourcePath> ghciInit;

  @AddToRuleKey BuildRule omnibusSharedObject;

  @AddToRuleKey ImmutableSortedMap<String, NonHashableSourcePathContainer> solibs;

  @AddToRuleKey ImmutableSortedMap<String, NonHashableSourcePathContainer> preloadLibs;

  @AddToRuleKey ImmutableSet<HaskellPackage> firstOrderHaskellPackages;

  @AddToRuleKey ImmutableSet<HaskellPackage> haskellPackages;

  @AddToRuleKey ImmutableSet<HaskellPackage> prebuiltHaskellPackages;

  @AddToRuleKey boolean enableProfiling;

  @AddToRuleKey ArchiveContents archiveContents;

  @AddToRuleKey(stringify = true)
  Path ghciScriptTemplate;

  @AddToRuleKey ImmutableList<SourcePath> extraScriptTemplates;

  @AddToRuleKey(stringify = true)
  Path ghciIservScriptTemplate;

  @AddToRuleKey(stringify = true)
  Path ghciBinutils;

  @AddToRuleKey(stringify = true)
  Path ghciGhc;

  @AddToRuleKey(stringify = true)
  Path ghciIServ;

  @AddToRuleKey(stringify = true)
  Path ghciIServProf;

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

  private HaskellGhciRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      HaskellSources srcs,
      ImmutableList<String> compilerFlags,
      Optional<SourcePath> ghciBinDep,
      Optional<SourcePath> ghciInit,
      BuildRule omnibusSharedObject,
      ImmutableSortedMap<String, SourcePath> solibs,
      ImmutableSortedMap<String, SourcePath> preloadLibs,
      ImmutableSet<HaskellPackage> firstOrderHaskellPackages,
      ImmutableSet<HaskellPackage> haskellPackages,
      ImmutableSet<HaskellPackage> prebuiltHaskellPackages,
      boolean enableProfiling,
      ArchiveContents archiveContents,
      Path ghciScriptTemplate,
      ImmutableList<SourcePath> extraScriptTemplates,
      Path ghciIservScriptTemplate,
      Path ghciBinutils,
      Path ghciGhc,
      Path ghciIServ,
      Path ghciIServProf,
      Path ghciLib,
      Path ghciCxx,
      Path ghciCc,
      Path ghciCpp,
      Path ghciPackager) {
    super(buildTarget, projectFilesystem, params);
    this.srcs = srcs;
    this.compilerFlags = compilerFlags;
    this.ghciBinDep = ghciBinDep;
    this.ghciInit = ghciInit;
    this.omnibusSharedObject = omnibusSharedObject;
    this.solibs = nonHashableSolibs(solibs);
    this.preloadLibs = nonHashableSolibs(preloadLibs);
    this.firstOrderHaskellPackages = firstOrderHaskellPackages;
    this.haskellPackages = haskellPackages;
    this.prebuiltHaskellPackages = prebuiltHaskellPackages;
    this.enableProfiling = enableProfiling;
    this.archiveContents = archiveContents;
    this.ghciScriptTemplate = ghciScriptTemplate;
    this.extraScriptTemplates = extraScriptTemplates;
    this.ghciIservScriptTemplate = ghciIservScriptTemplate;
    this.ghciBinutils = ghciBinutils;
    this.ghciGhc = ghciGhc;
    this.ghciIServ = ghciIServ;
    this.ghciIServProf = ghciIServProf;
    this.ghciLib = ghciLib;
    this.ghciCxx = ghciCxx;
    this.ghciCc = ghciCc;
    this.ghciCpp = ghciCpp;
    this.ghciPackager = ghciPackager;
  }

  private ImmutableSortedMap<String, NonHashableSourcePathContainer> nonHashableSolibs(
      ImmutableSortedMap<String, SourcePath> libs) {
    return libs.entrySet().stream()
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                Ordering.natural(),
                Entry::getKey,
                e -> new NonHashableSourcePathContainer(e.getValue())));
  }

  public static HaskellGhciRule from(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      HaskellSources srcs,
      ImmutableList<String> compilerFlags,
      Optional<SourcePath> ghciBinDep,
      Optional<SourcePath> ghciInit,
      BuildRule omnibusSharedObject,
      ImmutableSortedMap<String, SourcePath> solibs,
      ImmutableSortedMap<String, SourcePath> preloadLibs,
      ImmutableSet<HaskellPackage> firstOrderHaskellPackages,
      ImmutableSet<HaskellPackage> haskellPackages,
      ImmutableSet<HaskellPackage> prebuiltHaskellPackages,
      boolean enableProfiling,
      HaskellPlatform platform,
      ImmutableList<SourcePath> extraScriptTemplates) {

    ImmutableSet.Builder<BuildRule> extraDeps = ImmutableSet.builder();

    extraDeps.add(omnibusSharedObject);

    for (HaskellPackage pkg : haskellPackages) {
      extraDeps.addAll(pkg.getDeps(ruleFinder)::iterator);
    }

    for (HaskellPackage pkg : prebuiltHaskellPackages) {
      extraDeps.addAll(pkg.getDeps(ruleFinder)::iterator);
    }

    ghciBinDep.flatMap(ruleFinder::getRule).ifPresent(extraDeps::add);

    extraDeps.addAll(ruleFinder.filterBuildRuleInputs(solibs.values()));
    extraDeps.addAll(ruleFinder.filterBuildRuleInputs(preloadLibs.values()));
    return new HaskellGhciRule(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(extraDeps.build()),
        srcs,
        compilerFlags,
        ghciBinDep,
        ghciInit,
        omnibusSharedObject,
        solibs,
        preloadLibs,
        firstOrderHaskellPackages,
        haskellPackages,
        prebuiltHaskellPackages,
        enableProfiling,
        platform.getArchiveContents(),
        platform.getGhciScriptTemplate().get(),
        extraScriptTemplates,
        platform.getGhciIservScriptTemplate().get(),
        platform.getGhciBinutils().get(),
        platform.getGhciGhc().get(),
        platform.getGhciIServ().get(),
        platform.getGhciIServProf().get(),
        platform.getGhciLib().get(),
        platform.getGhciCxx().get(),
        platform.getGhciCc().get(),
        platform.getGhciCpp().get(),
        platform.getGhciPackager().get());
  }

  private Path getOutputDir() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getOutputDir());
  }

  /** Resolves the real path to the lib and generates a symlink to it */
  private class ResolveAndSymlinkStep extends AbstractExecutionStep {

    private SourcePathResolverAdapter resolver;
    private Path symlinkDir;
    private String name;
    private SourcePath lib;

    public ResolveAndSymlinkStep(
        SourcePathResolverAdapter resolver, Path symlinkDir, String name, SourcePath lib) {
      super("symlinkLib_" + name);
      this.resolver = resolver;
      this.symlinkDir = symlinkDir;
      this.name = name;
      this.lib = lib;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) throws IOException {
      Path src = resolver.getRelativePath(lib).toRealPath();
      Path dest = symlinkDir.resolve(name);
      return SymlinkFileStep.of(getProjectFilesystem(), src, dest).execute(context);
    }
  }

  private void symlinkLibs(
      SourcePathResolverAdapter resolver,
      Path symlinkDir,
      ImmutableList.Builder<Step> steps,
      ImmutableSortedMap<String, NonHashableSourcePathContainer> libs) {
    for (Map.Entry<String, NonHashableSourcePathContainer> ent : libs.entrySet()) {
      steps.add(
          new ResolveAndSymlinkStep(
              resolver, symlinkDir, ent.getKey(), ent.getValue().getSourcePath()));
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    SourcePathResolverAdapter resolver = context.getSourcePathResolver();

    String name = getBuildTarget().getShortName();
    Path dir = getOutputDir();
    Path so = resolver.getRelativePath(omnibusSharedObject.getSourcePathToOutput());
    Path packagesDir = dir.resolve(name + ".packages");
    Path symlinkDir = dir.resolve(HaskellGhciDescription.getSoLibsRelDir(getBuildTarget()));
    Path symlinkPreloadDir = dir.resolve(name + ".preload-symlinks");

    ImmutableList.Builder<String> compilerFlagsBuilder = ImmutableList.builder();
    compilerFlagsBuilder.addAll(compilerFlags);

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
                context.getBuildCellRootPath(), getProjectFilesystem(), symlinkPreloadDir)));
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), packagesDir)));

    steps.add(CopyStep.forFile(getProjectFilesystem(), so, dir.resolve(so.getFileName())));

    symlinkLibs(resolver, symlinkDir, steps, solibs);
    symlinkLibs(resolver, symlinkPreloadDir, steps, preloadLibs);

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

      ImmutableSet.Builder<SourcePath> artifacts = ImmutableSet.builder();
      artifacts.addAll(pkg.getLibraries());

      if (archiveContents == ArchiveContents.THIN) {
        // this is required because the .a files above are thin archives,
        // they merely point to the .o files via a relative path.
        artifacts.addAll(pkg.getObjects());
      }

      artifacts.addAll(pkg.getInterfaces());

      for (SourcePath artifact : artifacts.build()) {
        Path source = resolver.getRelativePath(artifact);
        Path destination = pkgdir.resolve(source.getParent().getFileName());
        steps.add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), destination)));
        steps.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                source,
                destination,
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      }

      pkgdirs.add("${DIR}/" + dir.relativize(pkgdir.resolve(pkgDbSrc.getFileName())));
    }

    ImmutableSet.Builder<String> exposedPkgs = ImmutableSet.builder();
    for (HaskellPackage pkg : firstOrderHaskellPackages) {
      exposedPkgs.add(String.format("%s-%s", pkg.getInfo().getName(), pkg.getInfo().getVersion()));
    }

    // iserv script
    Optional<Path> iservScript = Optional.empty();

    if (!preloadLibs.isEmpty()) {
      iservScript = Optional.of(dir.resolve("iserv"));
      compilerFlagsBuilder.add("-fexternal-interpreter");
      steps.add(
          new AbstractExecutionStep("ghci_iserv_wrapper") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              String template;
              template = new String(Files.readAllBytes(ghciIservScriptTemplate), Charsets.UTF_8);
              ST st = new ST(template);
              ImmutableSet.Builder<String> preloadLibrariesB = ImmutableSet.builder();
              for (String libPath : preloadLibs.keySet()) {
                preloadLibrariesB.add(
                    "${DIR}/" + dir.relativize(symlinkPreloadDir.resolve(libPath)));
              }
              ImmutableSet<String> preloadLibraries = preloadLibrariesB.build();
              st.add("name", name + "-iserv");
              st.add("preload_libs", Joiner.on(':').join(preloadLibraries));
              if (enableProfiling) {
                st.add("ghci_iserv_path", ghciIServProf.toRealPath().toString());
              } else {
                st.add("ghci_iserv_path", ghciIServ.toRealPath().toString());
              }
              Path actualIserv = dir.resolve("iserv");
              if (enableProfiling) {
                actualIserv = dir.resolve("iserv-prof");
              }
              return new WriteFileStep(
                      getProjectFilesystem(),
                      Objects.requireNonNull(st.render()),
                      actualIserv, /* executable */
                      true)
                  .execute(context);
            }
          });
    }

    // .ghci file
    StringBuilder startGhciContents = new StringBuilder();
    if (iservScript.isPresent()) {
      // Need to unset preloaded deps for `iserv`
      startGhciContents.append("System.Environment.unsetEnv \"LD_PRELOAD\"\n");
    }
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

    // ghciBinDep
    ImmutableList.Builder<String> srcpaths = ImmutableList.builder();
    for (SourcePath sp : srcs.getSourcePaths()) {
      srcpaths.add(resolver.getRelativePath(sp).toString());
    }

    String ghcPath = null;
    try {
      if (ghciBinDep.isPresent()) {

        Path binDir = dir.resolve(name + ".bin");
        Path bin = binDir.resolve("ghci");
        SourcePath sp = ghciBinDep.get();

        steps.addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), binDir)));

        steps.add(CopyStep.forFile(getProjectFilesystem(), resolver.getRelativePath(sp), bin));

        ghcPath = "${DIR}/" + dir.relativize(bin) + " -B" + ghciLib.toRealPath();
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
      compilerFlagsBuilder.addAll(HaskellDescriptionUtils.PROF_FLAGS);
    }
    compilerFlagsBuilder.addAll(HaskellDescriptionUtils.PIC_FLAGS);

    String ghc = ghcPath;
    ImmutableMap.Builder<String, String> templateArgs = ImmutableMap.builder();
    try {
      templateArgs.put("name", name);
      templateArgs.put("start_ghci", dir.relativize(startGhci).toString());
      templateArgs.put("exposed_packages", exposed);
      templateArgs.put("package_dbs", pkgdbs);
      templateArgs.put("compiler_flags", Joiner.on(' ').join(compilerFlagsBuilder.build()));
      templateArgs.put("srcs", Joiner.on(' ').join(srcpaths.build()));
      templateArgs.put("squashed_so", dir.relativize(dir.resolve(so.getFileName())).toString());
      templateArgs.put("binutils_path", ghciBinutils.toRealPath().toString());
      // ghc_path points to the ghc tool for this binary
      templateArgs.put("ghc_path", ghciGhc.toRealPath().toString());
      // user_ghci_path points to user-defined ghci binary, which can be eithe
      // the same as ghc_path, or a haskell_binary itself compiled during this
      // buck run.
      templateArgs.put("user_ghci_path", ghc);
      templateArgs.put("cxx_path", ghciCxx.toRealPath().toString());
      templateArgs.put("cc_path", ghciCc.toRealPath().toString());
      templateArgs.put("cpp_path", ghciCpp.toRealPath().toString());
      templateArgs.put("ghc_pkg_path", ghciPackager.toRealPath().toString());
      if (iservScript.isPresent()) {
        templateArgs.put("iserv_path", dir.relativize(iservScript.get()).toString());
      }
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }

    Path script = scriptPath();
    steps.add(
        new StringTemplateStep(
            ghciScriptTemplate, getProjectFilesystem(), script, templateArgs.build()));
    steps.add(new MakeExecutableStep(getProjectFilesystem(), script));

    for (SourcePath s : extraScriptTemplates) {
      Path templateAbsPath = resolver.getAbsolutePath(s);
      Path extraScript = dir.resolve(templateAbsPath.getFileName());
      steps.add(
          new StringTemplateStep(
              templateAbsPath, getProjectFilesystem(), extraScript, templateArgs.build()));
      steps.add(new MakeExecutableStep(getProjectFilesystem(), extraScript));
    }

    buildableContext.recordArtifact(dir);

    return steps.build();
  }

  private Path scriptPath() {
    return getOutputDir().resolve(getBuildTarget().getShortName());
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    SourcePath p = ExplicitBuildTargetSourcePath.of(getBuildTarget(), scriptPath());
    return new CommandTool.Builder().addArg(SourcePathArg.of(p)).build();
  }
}
