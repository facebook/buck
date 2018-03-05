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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.NonHashableSourcePathContainer;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MakeExecutableStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
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
import org.stringtemplate.v4.ST;

public class HaskellGhciRule extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule {

  @AddToRuleKey HaskellSources srcs;

  @AddToRuleKey ImmutableList<String> compilerFlags;

  @AddToRuleKey Optional<SourcePath> ghciBinDep;

  @AddToRuleKey Optional<SourcePath> ghciInit;

  @AddToRuleKey BuildRule omnibusSharedObject;

  ImmutableSortedMap<String, SourcePath> solibs;

  ImmutableSortedMap<String, SourcePath> preloadLibs;

  @AddToRuleKey ImmutableSet<HaskellPackage> firstOrderHaskellPackages;

  @AddToRuleKey ImmutableSet<HaskellPackage> haskellPackages;

  @AddToRuleKey ImmutableSet<HaskellPackage> prebuiltHaskellPackages;

  @AddToRuleKey boolean enableProfiling;

  @AddToRuleKey(stringify = true)
  Path ghciScriptTemplate;

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
      Path ghciScriptTemplate,
      Path ghciIservScriptTemplate,
      Path ghciBinutils,
      Path ghciGhc,
      Path ghciIServ,
      Path ghciIServProf,
      Path ghciLib,
      Path ghciCxx,
      Path ghciCc,
      Path ghciCpp) {
    super(buildTarget, projectFilesystem, params);
    this.srcs = srcs;
    this.compilerFlags = compilerFlags;
    this.ghciBinDep = ghciBinDep;
    this.ghciInit = ghciInit;
    this.omnibusSharedObject = omnibusSharedObject;
    this.solibs = solibs;
    this.preloadLibs = preloadLibs;
    this.firstOrderHaskellPackages = firstOrderHaskellPackages;
    this.haskellPackages = haskellPackages;
    this.prebuiltHaskellPackages = prebuiltHaskellPackages;
    this.enableProfiling = enableProfiling;
    this.ghciScriptTemplate = ghciScriptTemplate;
    this.ghciIservScriptTemplate = ghciIservScriptTemplate;
    this.ghciBinutils = ghciBinutils;
    this.ghciGhc = ghciGhc;
    this.ghciIServ = ghciIServ;
    this.ghciIServProf = ghciIServProf;
    this.ghciLib = ghciLib;
    this.ghciCxx = ghciCxx;
    this.ghciCc = ghciCc;
    this.ghciCpp = ghciCpp;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("links", solibsForRuleKey(solibs));
    sink.setReflectively("preloads", solibsForRuleKey(preloadLibs));
  }

  private ImmutableSortedMap<String, NonHashableSourcePathContainer> solibsForRuleKey(
      ImmutableSortedMap<String, SourcePath> libs) {
    ImmutableSortedMap.Builder<String, NonHashableSourcePathContainer> solibMap =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<String, SourcePath> entry : libs.entrySet()) {
      solibMap.put(entry.getKey(), new NonHashableSourcePathContainer(entry.getValue()));
    }

    return solibMap.build();
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
      Path ghciScriptTemplate,
      Path ghciIservScriptTemplate,
      Path ghciBinutils,
      Path ghciGhc,
      Path ghciIServ,
      Path ghciIServProf,
      Path ghciLib,
      Path ghciCxx,
      Path ghciCc,
      Path ghciCpp) {

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
        ghciScriptTemplate,
        ghciIservScriptTemplate,
        ghciBinutils,
        ghciGhc,
        ghciIServ,
        ghciIServProf,
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
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getOutputDir());
  }

  /** Resolves the real path to the lib and generates a symlink to it */
  private class ResolveAndSymlinkStep extends AbstractExecutionStep {

    private SourcePathResolver resolver;
    private Path symlinkDir;
    private String name;
    private SourcePath lib;

    public ResolveAndSymlinkStep(
        SourcePathResolver resolver, Path symlinkDir, String name, SourcePath lib) {
      super("symlinkLib_" + name);
      this.resolver = resolver;
      this.symlinkDir = symlinkDir;
      this.name = name;
      this.lib = lib;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      Path src = resolver.getRelativePath(lib).toRealPath();
      Path dest = symlinkDir.resolve(name);
      SymlinkFileStep.Builder sl = SymlinkFileStep.builder();
      return sl.setFilesystem(getProjectFilesystem())
          .setExistingFile(src)
          .setDesiredLink(dest)
          .build()
          .execute(context);
    }
  }

  private void symlinkLibs(
      SourcePathResolver resolver,
      Path symlinkDir,
      ImmutableList.Builder<Step> steps,
      ImmutableSortedMap<String, SourcePath> libs) {
    for (Map.Entry<String, SourcePath> ent : libs.entrySet()) {
      steps.add(new ResolveAndSymlinkStep(resolver, symlinkDir, ent.getKey(), ent.getValue()));
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    SourcePathResolver resolver = context.getSourcePathResolver();

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
            public StepExecutionResult execute(ExecutionContext context)
                throws IOException, InterruptedException {
              String template;
              template = new String(Files.readAllBytes(ghciIservScriptTemplate), Charsets.UTF_8);
              ST st = new ST(template);
              ImmutableSet.Builder<String> preloadLibrariesB = ImmutableSet.builder();
              for (Map.Entry<String, SourcePath> ent : preloadLibs.entrySet()) {
                preloadLibrariesB.add(
                    "${DIR}/" + dir.relativize(symlinkPreloadDir.resolve(ent.getKey())));
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
                      Preconditions.checkNotNull(st.render()),
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
      templateArgs.put("ghc_path", ghc);
      templateArgs.put("cxx_path", ghciCxx.toRealPath().toString());
      templateArgs.put("cc_path", ghciCc.toRealPath().toString());
      templateArgs.put("cpp_path", ghciCpp.toRealPath().toString());
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

    buildableContext.recordArtifact(dir);

    return steps.build();
  }

  private Path scriptPath() {
    return getOutputDir().resolve(getBuildTarget().getShortName());
  }

  @Override
  public Tool getExecutableCommand() {
    SourcePath p = ExplicitBuildTargetSourcePath.of(getBuildTarget(), scriptPath());
    return new CommandTool.Builder().addArg(SourcePathArg.of(p)).build();
  }
}
