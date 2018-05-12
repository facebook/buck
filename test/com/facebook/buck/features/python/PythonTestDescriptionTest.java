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

package com.facebook.buck.features.python;

import static org.junit.Assert.assertThat;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.CxxBinaryBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.ShBinary;
import com.facebook.buck.shell.ShBinaryBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PythonTestDescriptionTest {

  @Test
  public void thatTestModulesAreInComponents() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PythonTestBuilder builder =
        PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setSrcs(
                SourceList.ofUnnamedSources(ImmutableSortedSet.of(FakeSourcePath.of("blah.py"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PythonTest testRule = builder.build(resolver, filesystem, targetGraph);
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
                testRule.getBuildTarget(), PythonTestDescription.getTestMainName().toString())));
  }

  @Test
  public void baseModule() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    String sourceName = "main.py";
    SourcePath source = FakeSourcePath.of("foo/" + sourceName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    PythonTestBuilder normalBuilder =
        PythonTestBuilder.create(target)
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)));
    TargetGraph normalTargetGraph = TargetGraphFactory.newInstance(normalBuilder.build());
    PythonTest normal =
        normalBuilder.build(
            new TestBuildRuleResolver(normalTargetGraph), filesystem, normalTargetGraph);
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
        withBaseModuleBuilder.build(
            new TestBuildRuleResolver(withBaseModuleTargetGraph),
            filesystem,
            withBaseModuleTargetGraph);
    assertThat(
        withBaseModule.getBinary().getComponents().getModules().keySet(),
        Matchers.hasItem(Paths.get(baseModule).resolve(sourceName)));
  }

  @Test
  public void buildArgs() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    ImmutableList<String> buildArgs = ImmutableList.of("--some", "--args");
    PythonTestBuilder builder = PythonTestBuilder.create(target).setBuildArgs(buildArgs);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    PythonTest test = builder.build(resolver, filesystem, targetGraph);
    PythonBinary binary = test.getBinary();
    ImmutableList<? extends Step> buildSteps =
        binary.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());
    PexStep pexStep = RichStream.from(buildSteps).filter(PexStep.class).toImmutableList().get(0);
    assertThat(
        pexStep.getCommandPrefix(),
        Matchers.hasItems(buildArgs.toArray(new String[buildArgs.size()])));
  }

  @Test
  public void platformSrcs() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    SourcePath matchedSource = FakeSourcePath.of("foo/a.py");
    SourcePath unmatchedSource = FakeSourcePath.of("foo/b.py");
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
        builder.build(new TestBuildRuleResolver(targetGraph), filesystem, targetGraph);
    assertThat(
        test.getBinary().getComponents().getModules().values(),
        Matchers.allOf(
            Matchers.hasItem(matchedSource), Matchers.not(Matchers.hasItem(unmatchedSource))));
  }

  @Test
  public void platformResources() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:test");
    SourcePath matchedSource = FakeSourcePath.of("foo/a.dat");
    SourcePath unmatchedSource = FakeSourcePath.of("foo/b.dat");
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
        builder.build(new TestBuildRuleResolver(targetGraph), filesystem, targetGraph);
    assertThat(
        test.getBinary().getComponents().getResources().values(),
        Matchers.allOf(
            Matchers.hasItem(matchedSource), Matchers.not(Matchers.hasItem(unmatchedSource))));
  }

  @Test
  public void explicitPythonHome() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PythonPlatform platform1 =
        new TestPythonPlatform(
            InternalFlavor.of("pyPlat1"),
            new PythonEnvironment(Paths.get("python2.6"), PythonVersion.of("CPython", "2.6")),
            Optional.empty());
    PythonPlatform platform2 =
        new TestPythonPlatform(
            InternalFlavor.of("pyPlat2"),
            new PythonEnvironment(Paths.get("python2.7"), PythonVersion.of("CPython", "2.7")),
            Optional.empty());
    PythonTestBuilder builder =
        PythonTestBuilder.create(
            BuildTargetFactory.newInstance("//:bin"),
            FlavorDomain.of("Python Platform", platform1, platform2));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    PythonTest test1 =
        builder
            .setPlatform(platform1.getFlavor().toString())
            .build(new TestBuildRuleResolver(targetGraph), filesystem, targetGraph);
    assertThat(test1.getBinary().getPythonPlatform(), Matchers.equalTo(platform1));
    PythonTest test2 =
        builder
            .setPlatform(platform2.getFlavor().toString())
            .build(new TestBuildRuleResolver(targetGraph), filesystem, targetGraph);
    assertThat(test2.getBinary().getPythonPlatform(), Matchers.equalTo(platform2));
  }

  @Test
  public void runtimeDepOnDeps() {
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
              cxxBinaryBuilder.build(), pythonLibraryBuilder.build(), pythonTestBuilder.build());
      BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
      BuildRule cxxBinary = cxxBinaryBuilder.build(resolver, filesystem, targetGraph);
      pythonLibraryBuilder.build(resolver, filesystem, targetGraph);
      PythonTest pythonTest = pythonTestBuilder.build(resolver, filesystem, targetGraph);
      assertThat(
          String.format("Transitive runtime deps of %s [%s]", pythonTest, packageStyle.toString()),
          BuildRules.getTransitiveRuntimeDeps(pythonTest, resolver),
          Matchers.hasItem(cxxBinary.getBuildTarget()));
    }
  }

  @Test
  public void packageStyleParam() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PythonTestBuilder builder =
        PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setPackageStyle(PythonBuckConfig.PackageStyle.INPLACE);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PythonTest pythonTest = builder.build(resolver, filesystem, targetGraph);
    assertThat(pythonTest.getBinary(), Matchers.instanceOf(PythonInPlaceBinary.class));
    builder =
        PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    targetGraph = TargetGraphFactory.newInstance(builder.build());
    resolver = new TestBuildRuleResolver(targetGraph);
    pythonTest = builder.build(resolver, filesystem, targetGraph);
    assertThat(pythonTest.getBinary(), Matchers.instanceOf(PythonPackagedBinary.class));
  }

  @Test
  public void pexExecutorIsAddedToTestRuntimeDeps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ShBinaryBuilder pexExecutorBuilder =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:pex_executor"))
            .setMain(FakeSourcePath.of("run.sh"));
    PythonTestBuilder builder =
        PythonTestBuilder.create(
            BuildTargetFactory.newInstance("//:bin"),
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "python",
                        ImmutableMap.of(
                            "path_to_pex_executer", pexExecutorBuilder.getTarget().toString())))
                .build(),
            new AlwaysFoundExecutableFinder(),
            PythonTestUtils.PYTHON_PLATFORMS);
    builder.setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(pexExecutorBuilder.build(), builder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    ShBinary pexExecutor = pexExecutorBuilder.build(resolver);
    PythonTest binary = builder.build(resolver, filesystem, targetGraph);
    assertThat(
        binary
            .getRuntimeDeps(new SourcePathRuleFinder(resolver))
            .collect(ImmutableSet.toImmutableSet()),
        Matchers.hasItem(pexExecutor.getBuildTarget()));
  }

  @Test
  public void pexExecutorRuleIsAddedToParseTimeDeps() {
    ShBinaryBuilder pexExecutorBuilder =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:pex_executor"))
            .setMain(FakeSourcePath.of("run.sh"));
    PythonTestBuilder builder =
        PythonTestBuilder.create(
            BuildTargetFactory.newInstance("//:bin"),
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "python",
                        ImmutableMap.of(
                            "path_to_pex_executer", pexExecutorBuilder.getTarget().toString())))
                .build(),
            new AlwaysFoundExecutableFinder(),
            PythonTestUtils.PYTHON_PLATFORMS);
    builder.setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    assertThat(builder.build().getExtraDeps(), Matchers.hasItem(pexExecutorBuilder.getTarget()));
  }

  @Test
  public void pexBuilderAddedToParseTimeDeps() {
    BuildTarget pexBuilder = BuildTargetFactory.newInstance("//:pex_builder");
    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build()) {
          @Override
          public Optional<BuildTarget> getPexExecutorTarget() {
            return Optional.of(pexBuilder);
          }
        };

    PythonTestBuilder inplaceBinary =
        PythonTestBuilder.create(
                BuildTargetFactory.newInstance("//:bin"),
                config,
                new AlwaysFoundExecutableFinder(),
                PythonTestUtils.PYTHON_PLATFORMS)
            .setPackageStyle(PythonBuckConfig.PackageStyle.INPLACE);
    assertThat(inplaceBinary.findImplicitDeps(), Matchers.not(Matchers.hasItem(pexBuilder)));

    PythonTestBuilder standaloneBinary =
        PythonTestBuilder.create(
                BuildTargetFactory.newInstance("//:bin"),
                config,
                new AlwaysFoundExecutableFinder(),
                PythonTestUtils.PYTHON_PLATFORMS)
            .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE);
    assertThat(standaloneBinary.findImplicitDeps(), Matchers.hasItem(pexBuilder));
  }

  @Test
  public void versionedSrcs() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = FakeSourcePath.of("foo/a.py");
    SourcePath unmatchedSource = FakeSourcePath.of("foo/b.py");
    GenruleBuilder depBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep")).setOut("out");
    PythonTestBuilder builder =
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
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    depBuilder.build(resolver, filesystem, targetGraph);
    PythonTest test = builder.build(resolver, filesystem, targetGraph);
    assertThat(
        test.getBinary().getComponents().getModules().values(),
        Matchers.allOf(
            Matchers.hasItem(matchedSource), Matchers.not(Matchers.hasItem(unmatchedSource))));
  }

  @Test
  public void versionedResources() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = FakeSourcePath.of("foo/a.py");
    SourcePath unmatchedSource = FakeSourcePath.of("foo/b.py");
    GenruleBuilder depBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep")).setOut("out");
    PythonTestBuilder builder =
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
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    depBuilder.build(resolver, filesystem, targetGraph);
    PythonTest test = builder.build(resolver, filesystem, targetGraph);
    assertThat(
        test.getBinary().getComponents().getResources().values(),
        Matchers.allOf(
            Matchers.hasItem(matchedSource), Matchers.not(Matchers.hasItem(unmatchedSource))));
  }

  @Test
  public void targetGraphOnlyDepsDoNotAffectRuleKey() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    for (PythonBuckConfig.PackageStyle packageStyle : PythonBuckConfig.PackageStyle.values()) {

      // First, calculate the rule key of a python binary with no deps.
      PythonTestBuilder pythonTestBuilder =
          PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
              .setPackageStyle(packageStyle);
      TargetGraph targetGraph = TargetGraphFactory.newInstance(pythonTestBuilder.build());
      BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
      PythonTest pythonTestWithoutDep = pythonTestBuilder.build(resolver, filesystem, targetGraph);
      RuleKey ruleKeyWithoutDep = calculateRuleKey(resolver, pythonTestWithoutDep);

      // Next, calculate the rule key of a python binary with a deps on another binary.
      CxxBinaryBuilder cxxBinaryBuilder =
          new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"));
      pythonTestBuilder.setDeps(ImmutableSortedSet.of(cxxBinaryBuilder.getTarget()));
      targetGraph =
          TargetGraphFactory.newInstance(cxxBinaryBuilder.build(), pythonTestBuilder.build());
      resolver = new TestBuildRuleResolver(targetGraph);
      cxxBinaryBuilder.build(resolver, filesystem, targetGraph);
      PythonTest pythonBinaryWithDep = pythonTestBuilder.build(resolver, filesystem, targetGraph);
      RuleKey ruleKeyWithDep = calculateRuleKey(resolver, pythonBinaryWithDep);

      // Verify that the rule keys are identical.
      assertThat(ruleKeyWithoutDep, Matchers.equalTo(ruleKeyWithDep));
    }
  }

  @Test
  public void platformDeps() {
    SourcePath libASrc = FakeSourcePath.of("libA.py");
    PythonLibraryBuilder libraryABuilder =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:libA"))
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(libASrc)));
    SourcePath libBSrc = FakeSourcePath.of("libB.py");
    PythonLibraryBuilder libraryBBuilder =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:libB"))
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(libBSrc)));
    PythonTestBuilder binaryBuilder =
        PythonTestBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(
                            CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString(),
                            Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryABuilder.getTarget()))
                    .add(
                        Pattern.compile("matches nothing", Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            libraryABuilder.build(), libraryBBuilder.build(), binaryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PythonTest test = (PythonTest) resolver.requireRule(binaryBuilder.getTarget());
    assertThat(
        test.getBinary().getComponents().getModules().values(),
        Matchers.allOf(Matchers.hasItem(libASrc), Matchers.not(Matchers.hasItem(libBSrc))));
  }

  @Test
  public void cxxPlatform() {
    CxxPlatform platformA =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("platA"));
    CxxPlatform platformB =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("platB"));
    FlavorDomain<CxxPlatform> cxxPlatforms =
        FlavorDomain.from("C/C++ platform", ImmutableList.of(platformA, platformB));
    SourcePath libASrc = FakeSourcePath.of("libA.py");
    PythonLibraryBuilder libraryABuilder =
        new PythonLibraryBuilder(
                BuildTargetFactory.newInstance("//:libA"),
                PythonTestUtils.PYTHON_PLATFORMS,
                cxxPlatforms)
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(libASrc)));
    SourcePath libBSrc = FakeSourcePath.of("libB.py");
    PythonLibraryBuilder libraryBBuilder =
        new PythonLibraryBuilder(
                BuildTargetFactory.newInstance("//:libB"),
                PythonTestUtils.PYTHON_PLATFORMS,
                cxxPlatforms)
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(libBSrc)));
    PythonTestBuilder binaryBuilder =
        PythonTestBuilder.create(
                BuildTargetFactory.newInstance("//:bin"),
                PythonTestUtils.PYTHON_CONFIG,
                new ExecutableFinder(),
                PythonTestUtils.PYTHON_PLATFORMS,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                cxxPlatforms)
            .setCxxPlatform(platformA.getFlavor())
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(platformA.getFlavor().toString(), Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryABuilder.getTarget()))
                    .add(
                        Pattern.compile(platformB.getFlavor().toString(), Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            libraryABuilder.build(), libraryBBuilder.build(), binaryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PythonTest test = (PythonTest) resolver.requireRule(binaryBuilder.getTarget());
    assertThat(
        test.getBinary().getComponents().getModules().values(),
        Matchers.allOf(Matchers.hasItem(libASrc), Matchers.not(Matchers.hasItem(libBSrc))));
  }

  private RuleKey calculateRuleKey(BuildRuleResolver ruleResolver, BuildRule rule) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(
            new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create()),
            StackedFileHashCache.createDefaultHashCaches(
                rule.getProjectFilesystem(), FileHashCacheMode.DEFAULT),
            DefaultSourcePathResolver.from(ruleFinder),
            ruleFinder);
    return ruleKeyFactory.build(rule);
  }
}
