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

package com.facebook.buck.thrift;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ThriftLibraryDescriptionTest {

  private static FakeBuildRule createFakeBuildRule(
      BuildTarget target,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(target)
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build());
  }

  private static FakeBuildRule createFakeBuildRule(
      String target,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build());
  }

  private static SymlinkTree createFakeSymlinkTree(
      BuildTarget target,
      Path root,
      BuildRule... deps) {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build();
    return new SymlinkTree(params, root, ImmutableMap.<Path, SourcePath>of());
  }

  private static class FakeThriftLanguageSpecificEnhancer
      implements ThriftLanguageSpecificEnhancer {

    private final String language;
    private final Flavor flavor;
    private final ImmutableSet<BuildTarget> implicitDeps;
    private final ImmutableSet<String> options;

    public FakeThriftLanguageSpecificEnhancer(
        String language,
        Flavor flavor,
        ImmutableSet<BuildTarget> implicitDeps,
        ImmutableSet<String> options) {
      this.language = Preconditions.checkNotNull(language);
      this.flavor = Preconditions.checkNotNull(flavor);
      this.implicitDeps = Preconditions.checkNotNull(implicitDeps);
      this.options = Preconditions.checkNotNull(options);
    }

    @Override
    public String getLanguage() {
      return language;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }

    @SuppressWarnings("unused")
    public void checkCreateBuildRuleInputs(
        ImmutableMap<String, ThriftSource> sources,
        ImmutableSortedSet<BuildRule> deps) {}

    @Override
    public BuildRule createBuildRule(
        BuildRuleParams params,
        BuildRuleResolver resolver,
        ThriftConstructorArg args,
        ImmutableMap<String, ThriftSource> sources,
        ImmutableSortedSet<BuildRule> deps) {

      checkCreateBuildRuleInputs(sources, deps);

      return new FakeBuildRule(
          BuildRuleParamsFactory.createTrivialBuildRuleParams(
              BuildTargetFactory.newInstance("//:fake-lang")));
    }

    @Override
    public ImmutableSet<BuildTarget> getImplicitDepsFromParams(BuildRuleFactoryParams params) {
      return implicitDeps;
    }

    @Override
    public ImmutableSet<BuildTarget> getImplicitDepsFromArg(
        BuildTarget target,
        ThriftConstructorArg arg) {
      return implicitDeps;
    }

    @Override
    public ImmutableSet<String> getOptions(
        BuildTarget target,
        ThriftConstructorArg args) {
      return options;
    }

  }

  @Test
  public void createThriftCompilerBuildRulesHasCorrectDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    String language = "fake";
    Flavor flavor = new Flavor("fake");
    ImmutableSet<String> options = ImmutableSet.of();

    // Setup the default values returned by the language specific enhancer.
    BuildTarget unflavoredTarget = BuildTargetFactory.newInstance("//:thrift");
    BuildRuleParams unflavoredParams =
        new FakeBuildRuleParamsBuilder(unflavoredTarget)
            .setProjectFilesystem(filesystem)
            .build();

    // Setup a thrift source file generated by a genrule.
    BuildTarget flavoredTarget = BuildTargets.createFlavoredBuildTarget(unflavoredTarget, flavor);
    BuildRuleParams flavoredParams =
        new FakeBuildRuleParamsBuilder(flavoredTarget)
            .setProjectFilesystem(filesystem)
            .build();

    // Create a path for the thrift compiler.
    Path thriftPath = Paths.get("thrift_path");
    filesystem.touch(thriftPath);

    // Setup an thrift buck config, with the path to the thrift compiler set.
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "thrift", ImmutableMap.of("compiler_path", thriftPath.toString())),
        filesystem);
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);
    ThriftLibraryDescription desc = new ThriftLibraryDescription(
        thriftBuckConfig,
        ImmutableList.<ThriftLanguageSpecificEnhancer>of());

    // Setup a simple thrift source.
    String sourceName = "test.thrift";
    SourcePath sourcePath = new TestSourcePath(sourceName);

    // Generate these rules using no deps.
    ImmutableMap<String, ThriftCompiler> rules = desc.createThriftCompilerBuildRules(
        flavoredParams,
        resolver,
        ImmutableList.<String>of(),
        language,
        options,
        ImmutableMap.of(sourceName, sourcePath),
        ImmutableSortedSet.<ThriftLibrary>of());

    // Now verify that the generated rule had no associated deps.
    assertSame(rules.size(), 1);
    ThriftCompiler rule = rules.get(sourceName);
    assertNotNull(rule);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(),
        rule.getDeps());

    // Lets do this again, but pass in a ThriftLibrary deps, wrapping some includes we need.
    Path includeRoot = desc.getIncludeRoot(unflavoredTarget);
    SymlinkTree thriftIncludeSymlinkTree = createFakeSymlinkTree(
        desc.createThriftIncludeSymlinkTreeTarget(unflavoredTarget),
        includeRoot);
    ThriftLibrary lib = new ThriftLibrary(
        unflavoredParams,
        ImmutableSortedSet.<ThriftLibrary>of(),
        thriftIncludeSymlinkTree,
        ImmutableMap.<Path, SourcePath>of());

    // Generate these rules using no deps.
    rules = desc.createThriftCompilerBuildRules(
        flavoredParams,
        resolver,
        ImmutableList.<String>of(),
        language,
        options,
        ImmutableMap.of(sourceName, sourcePath),
        ImmutableSortedSet.of(lib));

    // Now verify that the generated rule has all the deps from the passed in thrift library.
    assertSame(rules.size(), 1);
    rule = rules.get(sourceName);
    assertNotNull(rule);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(thriftIncludeSymlinkTree),
        rule.getDeps());

    // Setup a simple genrule that creates the thrift source and verify its dep is propagated.
    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
        .setOut(sourceName)
        .build(resolver);
    SourcePath ruleSourcePath = new BuildRuleSourcePath(genrule);

    // Generate these rules using no deps and the genrule generated source.
    rules = desc.createThriftCompilerBuildRules(
        flavoredParams,
        resolver,
        ImmutableList.<String>of(),
        language,
        options,
        ImmutableMap.of(sourceName, ruleSourcePath),
        ImmutableSortedSet.<ThriftLibrary>of());

    // Now verify that the generated rule had no associated deps.
    assertSame(rules.size(), 1);
    rule = rules.get(sourceName);
    assertNotNull(rule);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule),
        rule.getDeps());

    // Create a build rule that represents the thrift rule.
    FakeBuildRule thriftRule = createFakeBuildRule("//thrift:target");
    resolver.addToIndex(thriftRule);
    filesystem.mkdirs(thriftRule.getBuildTarget().getBasePath());
    filesystem.touch(thriftRule.getBuildTarget().getBuildFilePath());

    // Setup an empty thrift buck config, and set compiler target.
    buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "thrift", ImmutableMap.of("compiler_target", thriftRule.getBuildTarget().toString())),
        filesystem);
    thriftBuckConfig = new ThriftBuckConfig(buckConfig);
    desc = new ThriftLibraryDescription(
        thriftBuckConfig,
        ImmutableList.<ThriftLanguageSpecificEnhancer>of());

    // Generate these rules using no deps with a compiler target.
    rules = desc.createThriftCompilerBuildRules(
        flavoredParams,
        resolver,
        ImmutableList.<String>of(),
        language,
        options,
        ImmutableMap.of(sourceName, sourcePath),
        ImmutableSortedSet.<ThriftLibrary>of());

    // Now verify that the generated rule only has deps from the compiler target.
    assertSame(rules.size(), 1);
    rule = rules.get(sourceName);
    assertNotNull(rule);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(thriftRule),
        rule.getDeps());
  }

  @Test
  public void createBuildRuleForUnflavoredTargetCreateThriftLibrary() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget unflavoredTarget = BuildTargetFactory.newInstance("//:thrift");
    BuildRuleParams unflavoredParams =
        BuildRuleParamsFactory.createTrivialBuildRuleParams(unflavoredTarget);

    // Setup an empty thrift buck config, missing the compiler.
    FakeBuckConfig buckConfig = new FakeBuckConfig(ImmutableMap.<String, String>of());
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);
    ThriftLibraryDescription desc = new ThriftLibraryDescription(
        thriftBuckConfig,
        ImmutableList.<ThriftLanguageSpecificEnhancer>of());

    // Setup the thrift source.
    String sourceName = "test.thrift";
    SourcePath source = new TestSourcePath(sourceName);

    // Create a dep and verify it gets attached.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    Path depIncludeRoot = desc.getIncludeRoot(depTarget);
    SymlinkTree depIncludeSymlinkTree = createFakeSymlinkTree(depTarget, depIncludeRoot);
    ThriftLibrary dep = new ThriftLibrary(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget),
        ImmutableSortedSet.<ThriftLibrary>of(),
        depIncludeSymlinkTree,
        ImmutableMap.<Path, SourcePath>of());
    resolver.addToIndex(dep);

    // Build up the constructor arg.
    ThriftConstructorArg arg = desc.createUnpopulatedConstructorArg();
    arg.name = "thrift";
    arg.srcs = ImmutableMap.of(source, ImmutableList.<String>of());
    arg.deps = Optional.of(ImmutableSortedSet.of(dep.getBuildTarget()));
    arg.flags = Optional.absent();

    // Build the thrift library rule and verify that it's setup correctly.
    BuildRule rule = desc.createBuildRule(unflavoredParams, resolver, arg);
    assertTrue(rule instanceof ThriftLibrary);
    ThriftLibrary me = (ThriftLibrary) rule;
    assertEquals(
        ImmutableSortedSet.of(dep),
        me.getThriftDeps());
    assertEquals(
        desc.getIncludeRoot(unflavoredTarget),
        me.getIncludeTreeRule().getRoot());
  }

  @Test
  public void createBuildRuleWithFlavoredTargetCallsEnhancerCorrectly() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup the default values returned by the language specific enhancer.
    String language = "fake";
    Flavor flavor = new Flavor("fake");
    final BuildRule implicitDep = createFakeBuildRule("//implicit:dep");
    resolver.addToIndex(implicitDep);
    filesystem.mkdirs(implicitDep.getBuildTarget().getBasePath());
    filesystem.touch(implicitDep.getBuildTarget().getBuildFilePath());
    ImmutableSet<BuildTarget> implicitDeps = ImmutableSet.of(implicitDep.getBuildTarget());
    ImmutableSet<String> options = ImmutableSet.of();

    // Create the build targets and params.
    BuildTarget unflavoredTarget = BuildTargetFactory.newInstance("//:thrift");
    BuildRuleParams unflavoredParams =
        new FakeBuildRuleParamsBuilder(unflavoredTarget)
            .setProjectFilesystem(filesystem)
            .build();
    BuildTarget flavoredTarget = BuildTargets.createFlavoredBuildTarget(unflavoredTarget, flavor);
    BuildRuleParams flavoredParams =
        new FakeBuildRuleParamsBuilder(flavoredTarget)
            .setProjectFilesystem(filesystem)
            .build();

    // Setup a thrift source file generated by a genrule.
    final String thriftSourceName1 = "foo.thrift";
    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//:genrule");
    final Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(genruleTarget)
        .setOut(thriftSourceName1)
        .build(resolver);
    SourcePath thriftSource1 = new BuildRuleSourcePath(genrule);
    final ImmutableList<String> thriftServices1 = ImmutableList.of();

    // Setup a normal thrift source file.
    final String thriftSourceName2 = "bar.thrift";
    SourcePath thriftSource2 = new TestSourcePath(thriftSourceName2);
    final ImmutableList<String> thriftServices2 = ImmutableList.of();

    // Create a build rule that represents the thrift rule.
    final FakeBuildRule thriftRule = createFakeBuildRule("//thrift:target");
    resolver.addToIndex(thriftRule);
    filesystem.mkdirs(thriftRule.getBuildTarget().getBasePath());
    filesystem.touch(thriftRule.getBuildTarget().getBuildFilePath());

    // Setup a simple description with an empty config.
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "thrift", ImmutableMap.of("compiler_target", thriftRule.getBuildTarget().toString())));
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);
    ThriftLibraryDescription desc = new ThriftLibraryDescription(
        thriftBuckConfig,
        ImmutableList.<ThriftLanguageSpecificEnhancer>of());

    // Setup the include rules.
    final BuildRule thriftIncludeSymlinkTree = createFakeBuildRule(
        desc.createThriftIncludeSymlinkTreeTarget(unflavoredTarget));

    // Setup our language enhancer
    FakeThriftLanguageSpecificEnhancer enhancer =
        new FakeThriftLanguageSpecificEnhancer(language, flavor, implicitDeps, options) {
          @Override
          public void checkCreateBuildRuleInputs(
              ImmutableMap<String, ThriftSource> sources,
              ImmutableSortedSet<BuildRule> deps) {

            // Verify both thrift sources are present in the list.
            assertEquals(2, sources.size());
            ThriftSource src1 = sources.get(thriftSourceName1);
            assertNotNull(src1);
            ThriftSource src2 = sources.get(thriftSourceName2);
            assertNotNull(src2);

            // Verify the services are listed correctly for both sources.
            assertEquals(thriftServices1, src1.getServices());
            assertEquals(thriftServices2, src2.getServices());

            // Verify dependencies are setup correctly.
            assertEquals(
                ImmutableSortedSet.of(
                    genrule,
                    thriftRule,
                    thriftIncludeSymlinkTree),
                src1.getCompileRule().getDeps());
            assertEquals(
                ImmutableSortedSet.of(
                    genrule,
                    thriftRule,
                    thriftIncludeSymlinkTree),
                src2.getCompileRule().getDeps());

            // Verify the language specific implicit rules are added correctly.
            assertEquals(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .add(implicitDep)
                    .build(),
                deps);
          }
        };

    // Recreate the description with the enhancer we setup above.
    desc = new ThriftLibraryDescription(
        thriftBuckConfig,
        ImmutableList.<ThriftLanguageSpecificEnhancer>of(enhancer));

    // Setup the internal structure indicating that the thrift target was set in the
    // buck config.
    //desc.setCompilerTarget(thriftRule.getBuildTarget());

    // Setup the implicit deps for the flavored build target.
    //desc.setImplicitDeps(flavoredTarget, ImmutableList.of(implicitDep.getBuildTarget()));

    // Build up the constructor arg.
    ThriftConstructorArg arg = desc.createUnpopulatedConstructorArg();
    arg.name = "thrift";
    arg.srcs = ImmutableMap.of(
        thriftSource1, thriftServices1,
        thriftSource2, thriftServices2);
    arg.deps = Optional.absent();
    arg.flags = Optional.absent();

    // Setup the unflavored target, which should just produce a ThriftInclude, SymlinkTree, and
    // ThriftLibrary rule.
    BuildRule rule = desc.createBuildRule(unflavoredParams, resolver, arg);
    resolver.addToIndex(rule);

    // Now attempt to create the flavored thrift library.
    desc.createBuildRule(flavoredParams, resolver, arg);
  }

  @Test
  public void findDepsFromParamsDoesNothingForUnflavoredTarget() {
    BuildTarget unflavoredTarget = BuildTargetFactory.newInstance("//:thrift");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTargetParser parser = new BuildTargetParser();
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        Maps.<String, Object>newHashMap(),
        filesystem,
        parser,
        unflavoredTarget,
        new FakeRuleKeyBuilderFactory());

    // Setup an empty thrift buck config and description.
    FakeBuckConfig buckConfig = new FakeBuckConfig();
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);
    ThriftLibraryDescription desc = new ThriftLibraryDescription(
        thriftBuckConfig,
        ImmutableList.<ThriftLanguageSpecificEnhancer>of());

    // Now call the find deps methods and verify it returns nothing.
    Iterable<String> results = desc.findDepsFromParams(params);
    assertEquals(
        ImmutableList.<String>of(),
        ImmutableList.copyOf(results));
  }

  @Test
  public void findDepsFromParamsSetsUpDepsForFlavoredTarget() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Create the thrift target and implicit dep.
    BuildTarget thriftTarget = BuildTargetFactory.newInstance("//bar:thrift_compiler");
    FakeBuildRule implicitDep = createFakeBuildRule("//foo:implicit_dep");

    // Setup the default values returned by the language specific enhancer.
    String language = "fake";
    Flavor flavor = new Flavor("fake");
    ImmutableSet<String> options = ImmutableSet.of();
    ImmutableSet<BuildTarget> implicitDeps = ImmutableSet.of(implicitDep.getBuildTarget());
    BuildTarget unflavoredTarget = BuildTargetFactory.newInstance("//:thrift");
    BuildTarget flavoredTarget =
        BuildTargets.createFlavoredBuildTarget(unflavoredTarget, flavor);

    // Create the build targets and params.
    BuildTargetParser parser = new BuildTargetParser();
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        Maps.<String, Object>newHashMap(),
        filesystem,
        parser,
        flavoredTarget,
        new FakeRuleKeyBuilderFactory());

    // Setup an empty thrift buck config and description.
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "thrift", ImmutableMap.of("compiler_target", thriftTarget.toString())));
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(buckConfig);
    ThriftLanguageSpecificEnhancer enhancer =
        new FakeThriftLanguageSpecificEnhancer(
            language,
            flavor,
            implicitDeps,
            options);
    ThriftLibraryDescription desc = new ThriftLibraryDescription(
        thriftBuckConfig,
        ImmutableList.of(enhancer));

    // Now call the find deps methods and verify it returns nothing.
    Iterable<String> results = desc.findDepsFromParams(params);
    assertEquals(
        FluentIterable.from(
            ImmutableSet.<BuildTarget>builder()
                .add(unflavoredTarget)
                .add(thriftTarget)
                .addAll(implicitDeps)
                .build())
            .transform(Functions.toStringFunction())
            .toSet(),
        ImmutableSet.copyOf(results));
  }

}
