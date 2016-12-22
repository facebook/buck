/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.python;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBinaryBuilder;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.ShBinary;
import com.facebook.buck.shell.ShBinaryBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;

public class PythonTestDescriptionTest {

  @Test
  public void thatTestModulesAreInComponents() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PythonTestBuilder builder =
        PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.of(new FakeSourcePath("blah.py"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PythonTest testRule = (PythonTest) builder.build(resolver, filesystem, targetGraph);
    PythonBinary binRule = testRule.getBinary();
    PythonPackageComponents components = binRule.getComponents();
    assertThat(
        components.getModules().keySet(),
        Matchers.hasItem(PythonTestDescription.getTestModulesListName()));
    assertThat(
        components.getModules().keySet(),
        Matchers.hasItem(PythonTestDescription.getTestMainName()));
    assertThat(
        binRule.getMainModule(),
        Matchers.equalTo(
            PythonUtil.toModuleName(
                testRule.getBuildTarget(),
                PythonTestDescription.getTestMainName().toString())));
  }

  @Test
  public void baseModule() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    String sourceName = "main.py";
    SourcePath source = new FakeSourcePath("foo/" + sourceName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    PythonTestBuilder normalBuilder =
        PythonTestBuilder.create(target)
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)));
    TargetGraph normalTargetGraph = TargetGraphFactory.newInstance(normalBuilder.build());
    PythonTest normal =
        (PythonTest) normalBuilder.build(
            new BuildRuleResolver(
                normalTargetGraph,
                new DefaultTargetNodeToBuildRuleTransformer()),
            filesystem,
            normalTargetGraph);
    assertThat(
        normal.getBinary().getComponents().getModules().keySet(),
        Matchers.hasItem(target.getBasePath().resolve(sourceName)));

    // Run *with* a base module set and verify it gets used to build the main module path.
    String baseModule = "blah";
    PythonTestBuilder withBaseModuleBuilder =
        PythonTestBuilder.create(target)
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)))
            .setBaseModule(baseModule);
    TargetGraph withBaseModuleTargetGraph =
        TargetGraphFactory.newInstance(withBaseModuleBuilder.build());
    PythonTest withBaseModule =
        (PythonTest) withBaseModuleBuilder.build(
            new BuildRuleResolver(
                withBaseModuleTargetGraph,
                new DefaultTargetNodeToBuildRuleTransformer()),
            filesystem,
            withBaseModuleTargetGraph);
    assertThat(
        withBaseModule.getBinary().getComponents().getModules().keySet(),
        Matchers.hasItem(Paths.get(baseModule).resolve(sourceName)));
  }

  @Test
  public void buildArgs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    ImmutableList<String> buildArgs = ImmutableList.of("--some", "--args");
    PythonTestBuilder builder =
        PythonTestBuilder.create(target)
            .setBuildArgs(buildArgs);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PythonTest test = (PythonTest) builder.build(resolver, filesystem, targetGraph);
    PythonBinary binary = test.getBinary();
    ImmutableList<Step> buildSteps =
        binary.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext());
    PexStep pexStep =
        RichStream.from(buildSteps)
            .filter(PexStep.class)
            .toImmutableList()
            .get(0);
    assertThat(
        pexStep.getCommandPrefix(),
        Matchers.hasItems(buildArgs.toArray(new String[buildArgs.size()])));
  }

  @Test
  public void platformSrcs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    SourcePath matchedSource = new FakeSourcePath("foo/a.py");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.py");
    PythonTestBuilder builder =
        PythonTestBuilder.create(target)
            .setPlatformSrcs(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(PythonTestUtils.PYTHON_PLATFORM.getFlavor().toString()),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    PythonTest test =
        (PythonTest) builder.build(
            new BuildRuleResolver(
                targetGraph,
                new DefaultTargetNodeToBuildRuleTransformer()),
            filesystem,
            targetGraph);
    assertThat(
        test.getBinary().getComponents().getModules().values(),
        Matchers.allOf(
            Matchers.hasItem(matchedSource),
            Matchers.not(Matchers.hasItem(unmatchedSource))));
  }

  @Test
  public void platformResources() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    SourcePath matchedSource = new FakeSourcePath("foo/a.dat");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.dat");
    PythonTestBuilder builder =
        PythonTestBuilder.create(target)
            .setPlatformResources(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(PythonTestUtils.PYTHON_PLATFORM.getFlavor().toString()),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    PythonTest test =
        (PythonTest) builder.build(
            new BuildRuleResolver(
                targetGraph,
                new DefaultTargetNodeToBuildRuleTransformer()),
            filesystem,
            targetGraph);
    assertThat(
        test.getBinary().getComponents().getResources().values(),
        Matchers.allOf(
            Matchers.hasItem(matchedSource),
            Matchers.not(Matchers.hasItem(unmatchedSource))));
  }

  @Test
  public void explicitPythonHome() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PythonPlatform platform1 =
        PythonPlatform.of(
            ImmutableFlavor.of("pyPlat1"),
            new PythonEnvironment(Paths.get("python2.6"), PythonVersion.of("CPython", "2.6")),
            Optional.empty());
    PythonPlatform platform2 =
        PythonPlatform.of(
            ImmutableFlavor.of("pyPlat2"),
            new PythonEnvironment(Paths.get("python2.7"), PythonVersion.of("CPython", "2.7")),
            Optional.empty());
    PythonTestBuilder builder =
        PythonTestBuilder.create(
            BuildTargetFactory.newInstance("//:bin"),
            FlavorDomain.of("Python Platform", platform1, platform2));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    PythonTest test1 =
        (PythonTest) builder
            .setPlatform(platform1.getFlavor().toString())
            .build(
                new BuildRuleResolver(
                    targetGraph,
                    new DefaultTargetNodeToBuildRuleTransformer()),
                filesystem,
                targetGraph);
    assertThat(test1.getBinary().getPythonPlatform(), Matchers.equalTo(platform1));
    PythonTest test2 =
        (PythonTest) builder
            .setPlatform(platform2.getFlavor().toString())
            .build(
                new BuildRuleResolver(
                    targetGraph,
                    new DefaultTargetNodeToBuildRuleTransformer()),
                filesystem,
                targetGraph);
    assertThat(test2.getBinary().getPythonPlatform(), Matchers.equalTo(platform2));
  }

  @Test
  public void runtimeDepOnDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    for (PythonBuckConfig.PackageStyle packageStyle : PythonBuckConfig.PackageStyle.values()) {
      CxxBinaryBuilder cxxBinaryBuilder =
          new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"));
      PythonLibraryBuilder pythonLibraryBuilder =
          new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
              .setDeps(ImmutableSortedSet.of(cxxBinaryBuilder.getTarget()));
      PythonTestBuilder pythonTestBuilder =
          PythonTestBuilder.create(BuildTargetFactory.newInstance("//:test"))
              .setDeps(ImmutableSortedSet.of(pythonLibraryBuilder.getTarget()))
              .setPackageStyle(packageStyle);
      TargetGraph targetGraph =
          TargetGraphFactory.newInstance(
              cxxBinaryBuilder.build(),
              pythonLibraryBuilder.build(),
              pythonTestBuilder.build());
      BuildRuleResolver resolver =
          new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
      BuildRule cxxBinary = cxxBinaryBuilder.build(resolver, filesystem, targetGraph);
      pythonLibraryBuilder.build(resolver, filesystem, targetGraph);
      PythonTest pythonTest =
          (PythonTest) pythonTestBuilder.build(resolver, filesystem, targetGraph);
      assertThat(
          String.format(
              "Transitive runtime deps of %s [%s]",
              pythonTest,
              packageStyle.toString()),
          BuildRules.getTransitiveRuntimeDeps(pythonTest),
          Matchers.hasItem(cxxBinary));
    }
  }

  @Test
  public void packageStyleParam() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PythonTestBuilder builder =
        PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setPackageStyle(PythonBuckConfig.PackageStyle.INPLACE);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    PythonTest pythonTest = (PythonTest) builder.build(resolver, filesystem, targetGraph);
    assertThat(
        pythonTest.getBinary(),
        Matchers.instanceOf(PythonInPlaceBinary.class));
    builder =
        PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    targetGraph = TargetGraphFactory.newInstance(builder.build());
    resolver = new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    pythonTest = (PythonTest) builder.build(resolver, filesystem, targetGraph);
    assertThat(
        pythonTest.getBinary(),
        Matchers.instanceOf(PythonPackagedBinary.class));
  }

  @Test
  public void pexExecutorIsAddedToTestRuntimeDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ShBinaryBuilder pexExecutorBuilder =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:pex_executor"))
            .setMain(new FakeSourcePath("run.sh"));
    PythonTestBuilder builder =
        new PythonTestBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new PythonBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(
                        ImmutableMap.of(
                            "python",
                            ImmutableMap.of(
                                "path_to_pex_executer",
                                pexExecutorBuilder.getTarget().toString())))
                    .build(),
                new AlwaysFoundExecutableFinder()),
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    builder
        .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            pexExecutorBuilder.build(),
            builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            targetGraph,
            new DefaultTargetNodeToBuildRuleTransformer());
    ShBinary pexExecutor = (ShBinary) pexExecutorBuilder.build(resolver);
    PythonTest binary = (PythonTest) builder.build(resolver, filesystem, targetGraph);
    assertThat(
        binary.getRuntimeDeps(),
        Matchers.hasItem(pexExecutor));
  }

  @Test
  public void pexExecutorRuleIsAddedToParseTimeDeps() throws Exception {
    ShBinaryBuilder pexExecutorBuilder =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:pex_executor"))
            .setMain(new FakeSourcePath("run.sh"));
    PythonTestBuilder builder =
        new PythonTestBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new PythonBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(
                        ImmutableMap.of(
                            "python",
                            ImmutableMap.of(
                                "path_to_pex_executer",
                                pexExecutorBuilder.getTarget().toString())))
                    .build(),
                new AlwaysFoundExecutableFinder()),
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    builder
        .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    assertThat(
        builder.build().getExtraDeps(),
        Matchers.hasItem(pexExecutorBuilder.getTarget()));
  }

  @Test
  public void pexBuilderAddedToParseTimeDeps() {
    final BuildTarget pexBuilder = BuildTargetFactory.newInstance("//:pex_builder");
    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new AlwaysFoundExecutableFinder()) {
          @Override
          public Optional<BuildTarget> getPexExecutorTarget() {
            return Optional.of(pexBuilder);
          }
        };

    PythonTestBuilder inplaceBinary =
        new PythonTestBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            config,
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS)
            .setPackageStyle(PythonBuckConfig.PackageStyle.INPLACE);
    assertThat(inplaceBinary.findImplicitDeps(), Matchers.not(Matchers.hasItem(pexBuilder)));

    PythonTestBuilder standaloneBinary =
        new PythonTestBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            config,
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS)
            .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    assertThat(standaloneBinary.findImplicitDeps(), Matchers.hasItem(pexBuilder));
  }

  @Test
  public void versionedSrcs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = new FakeSourcePath("foo/a.py");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.py");
    GenruleBuilder depBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out");
    AbstractNodeBuilder<?, ?> builder =
        PythonTestBuilder.create(target)
            .setVersionedSrcs(
                VersionMatchedCollection.<SourceList>builder()
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("2.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build())
            .setSelectedVersions(ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(depBuilder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            targetGraph,
            new DefaultTargetNodeToBuildRuleTransformer());
    depBuilder.build(resolver, filesystem, targetGraph);
    PythonTest test = (PythonTest) builder.build(resolver, filesystem, targetGraph);
    assertThat(
        test.getBinary().getComponents().getModules().values(),
        Matchers.allOf(
            Matchers.hasItem(matchedSource),
            Matchers.not(Matchers.hasItem(unmatchedSource))));
  }

  @Test
  public void versionedResources() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = new FakeSourcePath("foo/a.py");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.py");
    GenruleBuilder depBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out");
    AbstractNodeBuilder<?, ?> builder =
        PythonTestBuilder.create(target)
            .setVersionedResources(
                VersionMatchedCollection.<SourceList>builder()
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("2.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build())
            .setSelectedVersions(ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(depBuilder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            targetGraph,
            new DefaultTargetNodeToBuildRuleTransformer());
    depBuilder.build(resolver, filesystem, targetGraph);
    PythonTest test = (PythonTest) builder.build(resolver, filesystem, targetGraph);
    assertThat(
        test.getBinary().getComponents().getResources().values(),
        Matchers.allOf(
            Matchers.hasItem(matchedSource),
            Matchers.not(Matchers.hasItem(unmatchedSource))));
  }

}
