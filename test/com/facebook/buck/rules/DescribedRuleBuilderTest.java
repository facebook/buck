/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static com.facebook.buck.testutil.IdentityPathAbsolutifier.getIdentityAbsolutifier;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

public class DescribedRuleBuilderTest extends EasyMockSupport {

  @Test
  public void testBuildRuleForBuildableWithNoEnhancedDepsHasOriginalDeps()
      throws NoSuchBuildTargetException {
    BuildRuleResolver ruleResolver = createBuildRuleResolver();

    // Create the Buildable for the NominalDescription and verify its data.
    Description<Arg> description = new NominalDescription();
    ImmutableList<String> deps = ImmutableList.of("//my:library");
    BuildRuleFactoryParams params = createParamsWithDeps(deps);
    DescribedRuleBuilder<Arg> describedRuleBuilder = new DescribedRuleBuilder<>(description,
        params);
    BuildRule buildRule = describedRuleBuilder.build(ruleResolver);
    assertEquals(
        "If getEnhancedDeps() returns null, then the original deps should be used for the rule.",
        ImmutableSet.of("//my:sourcepath", "//my:library"),
        FluentIterable.from(buildRule.getDeps()).transform(Functions.toStringFunction()).toSet());
    verifyAll();
  }

  @Test
  public void testIKnowWhatIAmDoingAndIWillSpecifyAllTheDepsMyselfRedefinesTheDeps()
      throws NoSuchBuildTargetException {
    BuildRuleResolver ruleResolver = createBuildRuleResolver();

    // Create the Buildable for the DemandingNominalDescription and verify its data.
    Description<Arg> description = new DemandingNominalDescription();
    ImmutableList<String> deps = ImmutableList.of("//my:library");
    BuildRuleFactoryParams params = createParamsWithDeps(deps);
    DescribedRuleBuilder<Arg> describedRuleBuilder = new DescribedRuleBuilder<>(description,
        params);
    BuildRule buildRule = describedRuleBuilder.build(ruleResolver);
    assertEquals(
        "If iKnowWhatIAmDoingAndIWillSpecifyAllTheDepsMyself() returns non-null, then its " +
            "return value should be used as the deps.",
        ImmutableSet.of("//my:unrelated"),
        FluentIterable.from(buildRule.getDeps()).transform(Functions.toStringFunction()).toSet());
    verifyAll();
  }

  @Test
  public void testBuildRuleForBuildableWithEnhancedDepsHasCorrectDeps()
      throws NoSuchBuildTargetException {
    BuildRuleResolver ruleResolver = createBuildRuleResolver();

    // Create the FileCollector and verify its data.
    Description<Arg> description = new FileCollectorDescription();
    ImmutableList<String> deps = ImmutableList.of("//my:library");
    BuildRuleFactoryParams params = createParamsWithDeps(deps);
    DescribedRuleBuilder<Arg> describedRuleBuilder = new DescribedRuleBuilder<>(description,
        params);
    BuildRule buildRule = describedRuleBuilder.build(ruleResolver);
    assertEquals(
        "The build rule should have only the implicit deps.",
        ImmutableSet.of("//my:sourcepath"),
        FluentIterable.from(buildRule.getDeps()).transform(Functions.toStringFunction()).toSet());
    assertEquals(
        "The Buildable should collect files of interest from the original deps.",
        ImmutableSet.of(Paths.get("my/fileofinterest.txt")),
        ((FileCollector) buildRule.getBuildable()).filesOfInterest);
    verifyAll();
  }

  private static BuildRuleResolver createBuildRuleResolver() {
    // Create a rule with some satellite data and seed a BuildRuleResolver with it.
    BuildRuleWithInterestingFile dep = new BuildRuleWithInterestingFile(
        new FakeBuildRuleParamsBuilder(new BuildTarget("//my", "library")).build(),
        Paths.get("my/fileofinterest.txt"));

    FakeBuildRule fakeGenrule = new FakeBuildRule(
        new BuildRuleType("fake_genrule"),
        new BuildTarget("//my", "sourcepath"));
    fakeGenrule.setOutputFile("some_file.txt");

    FakeBuildRule fakeRule = new FakeBuildRule(
        new BuildRuleType("fake_rule"),
        new BuildTarget("//my", "unrelated"));

    return new BuildRuleResolver(ImmutableSet.of(dep, fakeGenrule, fakeRule));
  }

  private BuildRuleFactoryParams createParamsWithDeps(ImmutableList<String> deps) {
    // Create the raw data for a FileCollector.
    String shortName = "data";
    Map<String, Object> instance = Maps.newHashMap();
    instance.put("name", shortName);
    instance.put("srcs", ImmutableSortedSet.of("//my:sourcepath"));
    instance.put("deps", deps);
    instance.put("visibility", ImmutableList.of());

    // Build up the params to construct a FileCollector via a DescribedRuleBuilder.
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.exists(Paths.get("my"))).andStubReturn(true);
    EasyMock.expect(projectFilesystem.exists(Paths.get("my/BUCK"))).andStubReturn(true);
    EasyMock.expect(projectFilesystem.getAbsolutifier()).andStubReturn(getIdentityAbsolutifier());

    replayAll();
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        instance,
        projectFilesystem,
        new BuildTargetParser(projectFilesystem),
        new BuildTarget("//my", shortName),
        new FakeRuleKeyBuilderFactory());
    return params;
  }

  private static class Arg implements ConstructorArg {
    @SuppressWarnings("unused")
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }

  /**
   * {@link Description} that produces a {@link Buildable} which does not attempt graph enhancement.
   */
  private static class NominalDescription implements Description<Arg> {

    @Override
    public BuildRuleType getBuildRuleType() {
      return new BuildRuleType("nominal");
    }

    @Override
    public Arg createUnpopulatedConstructorArg() {
      return new Arg();
    }

    @Override
    public Buildable createBuildable(BuildRuleParams params, Arg args) {
      return new FakeBuildable(params.getBuildTarget());
    }
  }

  private abstract static class FakeBuildableWithHasDepsOverride extends FakeBuildable
      implements DependencyEnhancer {
    public FakeBuildableWithHasDepsOverride(BuildTarget target) {
      super(target);
    }
  }

  /**
   * {@link Description} that produces a {@link Buildable} whose
   * {@link DependencyEnhancer#getEnhancedDeps(BuildRuleResolver, Iterable, Iterable)} returns
   * a specific {@link BuildRule}.
   */
  private static class DemandingNominalDescription extends NominalDescription {
    @Override
    public Buildable createBuildable(BuildRuleParams params, Arg args) {
      return new FakeBuildableWithHasDepsOverride(params.getBuildTarget()) {
        @Override
        public ImmutableSortedSet<BuildRule> getEnhancedDeps(
            BuildRuleResolver ruleResolver,
            Iterable<BuildRule> declaredDeps,
            Iterable<BuildRule> inferredDeps) {
          return ImmutableSortedSet.<BuildRule>naturalOrder()
              .add(ruleResolver.get(new BuildTarget("//my", "unrelated")))
              .build();
        }
      };
    }
  }

  /**
   * Assume this is a Buildable that needs the files from its deps in order to build itself, but
   * does not need the deps to be built before it can start building itself.
   */
  private static class FileCollector extends FakeBuildable implements DependencyEnhancer{

    private final ImmutableSet<Path> filesOfInterest;
    private final ImmutableSortedSet<BuildRule> deps;

    /** @param filesOfInterest from deps of type {@link BuildRuleWithInterestingFile}. */
    FileCollector(
        BuildTarget target,
        Set<Path> filesOfInterest,
        ImmutableSortedSet<BuildRule> deps) {
      super(target);
      this.filesOfInterest = ImmutableSet.copyOf(filesOfInterest);
      this.deps = Preconditions.checkNotNull(deps);
    }

    @Override
    public ImmutableSortedSet<BuildRule> getEnhancedDeps(
        BuildRuleResolver ruleResolver,
        Iterable<BuildRule> declaredDeps,
        Iterable<BuildRule> inferredDeps) {
      return ImmutableSortedSet.<BuildRule>naturalOrder()
          .addAll(inferredDeps)
          .addAll(deps)
          .build();
    }
  }

  private static class FileCollectorDescription implements Description<Arg> {

    @Override
    public BuildRuleType getBuildRuleType() {
      return new BuildRuleType("example");
    }

    @Override
    public Arg createUnpopulatedConstructorArg() {
      return new Arg();
    }

    @Override
    public Buildable createBuildable(BuildRuleParams params, Arg args) {
      ImmutableSet.Builder<Path> filesOfInterest = ImmutableSet.builder();
      ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
      for (BuildRule dep : args.deps.get()) {
        if (dep instanceof BuildRuleWithInterestingFile) {
          Path fileOfInterest = ((BuildRuleWithInterestingFile) dep).pathToFileOfInterest;
          filesOfInterest.add(fileOfInterest);
        } else {
          deps.add(dep);
        }
      }

      return new FileCollector(params.getBuildTarget(), filesOfInterest.build(), deps.build());
    }
  }

  /**
   * Assume that this rule is potentially expensive to build, which is why {@link FileCollector}
   * does not want to wait for it.
   */
  private static class BuildRuleWithInterestingFile extends FakeBuildRule {

    private static final BuildRuleType TYPE = new BuildRuleType("fake_build_rule");

    private final Path pathToFileOfInterest;

    public BuildRuleWithInterestingFile(BuildRuleParams buildRuleParams,
        Path pathToFileOfInterest) {
      super(TYPE, buildRuleParams);
      this.pathToFileOfInterest = Preconditions.checkNotNull(pathToFileOfInterest);
    }
  }
}
