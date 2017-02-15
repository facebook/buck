/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.go;

import com.facebook.buck.cxx.CxxBinary;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLinkAndCompileRules;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public class CGoCompile extends NoopBuildRule {
  public final ImmutableList<SourcePath> generatedGoSrcs;
  private final Path output;

  public CGoCompile(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      ImmutableList<SourcePath> generatedGoSrcs,
      Path output
  ) {
    super(params, pathResolver);
    this.generatedGoSrcs = generatedGoSrcs;
    this.output = output;
  }

  private static SourcePath generatedSource(
      BuildRuleResolver resolver,
      BuildRuleParams generator,
      Path futurePath) {

    class NoopWithOutputName extends AbstractBuildRule implements HasOutputName {
      private final Path output;

      protected NoopWithOutputName(
          BuildRuleParams buildRuleParams,
          Path output) {
        super(buildRuleParams);
        this.output = output;
      }

      @Override
      public String getOutputName() {
        return output.toAbsolutePath().toString();
      }

      @Override
      public ImmutableList<Step> getBuildSteps(
          BuildContext context, BuildableContext buildableContext) {
        return ImmutableList.of();
      }

      @Nullable
      @Override
      public Path getPathToOutput() {
        return output;
      }
    }

    BuildTarget target = generator.getBuildTarget()
        .withFlavors(ImmutableFlavor.of(futurePath.getFileName().toString()));
    BuildRule rule = new NoopWithOutputName(
        generator
            .copyWithBuildTarget(target)
            .appendExtraDeps(resolver.getRule(generator.getBuildTarget())),
        futurePath
    );

    resolver.addToIndex(rule);

    return new BuildTargetSourcePath(target, futurePath);
  }


  private static ImmutableSortedSet<SourceWithFlags> prepareGeneratedSources(
      Iterable<SourcePath> it) {
    ImmutableSortedSet.Builder<SourceWithFlags> builder = ImmutableSortedSet.naturalOrder();
    for (SourcePath p : it) {
      builder.add(
          SourceWithFlags.builder().setSourcePath(p).build()
      );
    }
    return builder.build();
  }

  private static BuildRule nativeBinCompilation(
      BuildRuleResolver resolver,
      ImmutableList<SourcePath> srcs,
      CxxBinaryDescription cxxBinaryDescription,
      BuildRuleParams params,
      BuildTarget buildTarget,
      String step,
      ImmutableSortedSet<BuildRule> deps,
      Flags flags,
      ImmutableSortedSet<BuildTarget> nativeDeps) throws NoSuchBuildTargetException {
    CxxBinaryDescription.Arg args = new CxxBinaryDescription.Arg();
    args.linkStyle = Optional.empty();

    args.srcs = prepareGeneratedSources(srcs);
    args.deps = nativeDeps;

    args.langCompilerFlags = ImmutableMap.of(
        CxxSource.Type.C,
        FluentIterable.from(flags.cflags).toList(),
        CxxSource.Type.CXX,
        FluentIterable.from(flags.cxxflags).toList()
    );

    args.linkerFlags = FluentIterable.from(flags.ldflags).toList();

    BuildRuleParams binParams = params.copyWithBuildTarget(
        buildTarget.withFlavors(ImmutableFlavor.of(step)))
        .appendExtraDeps(deps);

    CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
            binParams,
            resolver,
            cxxBinaryDescription.getCxxBuckConfig(),
            cxxBinaryDescription.getDefaultCxxPlatform(),
            args,
            Optional.empty(),
            Optional.empty()
        );

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    ImmutableCollection<BuildRule> executableDeps = cxxLinkAndCompileRules.executable.getDeps(ruleFinder);
    CxxBinary cxxBinary = new CxxBinary(
        binParams.appendExtraDeps(executableDeps),
        resolver,
        ruleFinder,
        cxxLinkAndCompileRules.getBinaryRule(),
        cxxLinkAndCompileRules.executable,
        args.frameworks,
        args.tests,
        binParams.getBuildTarget().withoutFlavors(cxxBinaryDescription.getCxxPlatforms().getFlavors()));
    return cxxBinary;
  }

  public static BuildRule createBuildRule(
      BuildRuleResolver resolver,
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      Path packageName,
      ImmutableSet<SourcePath> cgoSrcs,
      Tool cgo,
      GoPlatform platform,
      CxxBinaryDescription cxxBinaryDescription,
      ImmutableSortedSet<BuildTarget> nativeDeps) throws NoSuchBuildTargetException {

    Path cgoGenDir = BuildTargets.getGenPath(
        params.getProjectFilesystem(),
        params.getBuildTarget(),
        "%s/gen/");

    BuildRuleParams cParams =
        params
            .withFlavor(ImmutableFlavor.of("cgo-gen-source"));
    BuildRule genCSource = new GenCSource(
        cParams,
        pathResolver,
        cgoSrcs,
        cgo,
        platform,
        cgoGenDir);
    resolver.addToIndex(genCSource);

    ImmutableList.Builder<SourcePath> goBuilder = ImmutableList.builder();
    ImmutableList.Builder<SourcePath> commonCBuilder = ImmutableList.builder();
    ImmutableList.Builder<SourcePath> cgoCBuilder = ImmutableList.builder();

    goBuilder.add(generatedSource(
        resolver,
        cParams,
        cgoGenDir.resolve("_cgo_gotypes.go")));
    goBuilder.add(generatedSource(
        resolver,
        cParams,
        cgoGenDir.resolve("_cgo_import.go")));

    for (SourcePath sp : cgoSrcs) {
      Path p = pathResolver.getAbsolutePath(sp);
      // cgo generates 2 files for each Go sources, 1 .cgo1.go and 1 .cgo2.c
      String[] split = p.toAbsolutePath()
          .toString()
          .replace(File.separatorChar, '_')
          .split(Pattern.quote("."));

      String filename = split[0];
      goBuilder.add(generatedSource(
          resolver,
          cParams,
          cgoGenDir.resolve(filename + ".cgo1.go")));
      commonCBuilder.add(generatedSource(
          resolver,
          cParams,
          cgoGenDir.resolve(filename + ".cgo2.c")));
    }
    commonCBuilder.add(generatedSource(resolver, cParams, cgoGenDir.resolve("_cgo_export.c")));
    cgoCBuilder.add(generatedSource(resolver, cParams, cgoGenDir.resolve("_cgo_main.c")));

    ImmutableList<SourcePath> cgoAllCCSrcs = commonCBuilder.build();
    ImmutableList<SourcePath> cgoCSrcs =
        FluentIterable.from(cgoAllCCSrcs).append(cgoCBuilder.build()).toList();
    ImmutableList<SourcePath> generatedGoSrcs = goBuilder.build();


    BuildRule cgoBin = nativeBinCompilation(
        resolver,
        cgoCSrcs,
        cxxBinaryDescription,
        params,
        params.getBuildTarget(),
        "cgo-first-step",
        ImmutableSortedSet.of(genCSource),
        Flags.firstStep(platform),
        nativeDeps);

    BuildRule cgoImport = new GenGoImport(
        params
            .withFlavor(ImmutableFlavor.of("cgo-gen-cgo_import.go"))
            .appendExtraDeps(cgoBin),
        pathResolver,
        cgo,
        platform,
        packageName,
        cgoGenDir);

    BuildRule allBin = nativeBinCompilation(
        resolver,
        cgoAllCCSrcs,
        cxxBinaryDescription,
        params,
        params.getBuildTarget(),
        "cgo-second-step",
        ImmutableSortedSet.of(cgoImport),
        Flags.secondStep(platform),
        ImmutableSortedSet.of()
    );

    BuildRule entryPoint = new CGoCompile(
        params
            .appendExtraDeps(allBin),
        pathResolver,
        generatedGoSrcs,
        allBin.getPathToOutput());

    return entryPoint;
  }

  public ImmutableList<SourcePath> getGeneratedGoSource() {
    return this.generatedGoSrcs;
  }

  public Path getOutputBinary() {
    return this.output;
  }

  static final class Flags {
    public ImmutableSet<String> cflags = ImmutableSet.of();
    public ImmutableSet<String> cxxflags = ImmutableSet.of();
    public ImmutableSet<String> ldflags = ImmutableSet.of();

    @SuppressWarnings("unused")
    static Flags firstStep(GoPlatform platform) {
      Flags flags = new Flags();
      return flags;
    }

    static Flags secondStep(GoPlatform platform) {
      Flags flags = new Flags();
      switch (platform.getGoOs()) {
        case "darwin":
          flags.ldflags = ImmutableSet.of("-r", "-nostdlib");
          break;
        default:
          flags.ldflags = ImmutableSet.of("-r", "-nostdlib", "-no-pie");
          break;
      }

      return flags;
    }
  }

  private static class GenCSource extends AbstractBuildRule {
    private SourcePathResolver pathResolver;
    @AddToRuleKey
    private final ImmutableSet<SourcePath> cgoSrcs;
    private final Tool cgo;
    private final GoPlatform platform;
    private final Path genDir;

    public GenCSource(
        BuildRuleParams params,
        SourcePathResolver pathResolver,
        ImmutableSet<SourcePath> cgoSrcs,
        Tool cgo,
        GoPlatform platform,
        Path genDir
    ) {
      super(params);
      this.pathResolver = pathResolver;
      this.cgoSrcs = cgoSrcs;
      this.cgo = cgo;
      this.platform = platform;
      this.genDir = genDir;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {

      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), genDir));
      steps.add(new CGoCompileStep(
          getProjectFilesystem().getRootPath(),
          cgo.getEnvironment(),
          cgo.getCommandPrefix(this.pathResolver),
          FluentIterable.from(cgoSrcs).transform(p -> this.pathResolver.getAbsolutePath(p)).toList(),
          platform,
          genDir));

      return steps.build();
    }

    @Nullable
    @Override
    public Path getPathToOutput() {
      return genDir;
    }
  }

  private static class GenGoImport extends AbstractBuildRule {
    private SourcePathResolver pathResolver;
    private final Tool cgo;
    private final GoPlatform platform;
    private final Path packageName;
    private final Path outputFile;

    public GenGoImport(
        BuildRuleParams params,
        SourcePathResolver pathResolver,
        Tool cgo,
        GoPlatform platform,
        Path packageName,
        Path outputDir
    ) {
      super(params);
      this.pathResolver = pathResolver;
      this.cgo = cgo;
      this.platform = platform;
      this.packageName = packageName;

      this.outputFile = outputDir.resolve("_cgo_import.go");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {

      ImmutableList<Path> binaries = FluentIterable.from(getDeps())
          .filter(CxxBinary.class)
          .transform(CxxBinary::getPathToOutput)
          .toList();

      if (binaries.size() > 1) {
        throw new HumanReadableException(
            "_cgo_import.go can be generated from only one binary, %i binaries found",
            binaries.size());
      }

      final Path binary = binaries.get(0);
      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      steps.add(new CGoGenerateImportStep(
          getProjectFilesystem().getRootPath(),
          cgo.getEnvironment(),
          cgo.getCommandPrefix(this.pathResolver),
          platform,
          packageName,
          binary,
          this.outputFile
      ));
      return steps.build();
    }

    @Nullable
    @Override
    public Path getPathToOutput() {
      return this.outputFile;
    }
  }
}
