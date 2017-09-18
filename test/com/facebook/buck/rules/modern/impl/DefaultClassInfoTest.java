/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.impl;

import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.*;

import com.facebook.buck.event.EventDispatcher;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.InputDataRetriever;
import com.facebook.buck.rules.modern.InputPath;
import com.facebook.buck.rules.modern.InputPathResolver;
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputData;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.hamcrest.Matchers;
import org.junit.Test;

@SuppressWarnings("unused")
public class DefaultClassInfoTest {
  private InputRuleResolver inputRuleResolver = createStrictMock(InputRuleResolver.class);

  @SuppressWarnings("unchecked")
  private Consumer<BuildRule> buildRuleConsumer = createStrictMock(Consumer.class);

  @SuppressWarnings("unchecked")
  private BiConsumer<String, OutputPath> outputConsumer = createStrictMock(BiConsumer.class);

  @SuppressWarnings("unchecked")
  private BiConsumer<String, OutputData> outputDataConsumer = createStrictMock(BiConsumer.class);

  private RuleKeyObjectSink ruleKeyObjectSink = createStrictMock(RuleKeyObjectSink.class);

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();

  static class NoOpBuildable implements Buildable {
    @Override
    public ImmutableList<Step> getBuildSteps(
        EventDispatcher eventDispatcher,
        ProjectFilesystem filesystem,
        InputPathResolver inputPathResolver,
        InputDataRetriever inputDataRetriever,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of();
    }
  }

  private abstract static class BaseClass extends NoOpBuildable {
    private static final String BASE_NAME = "BaseClass";
    private final InputPath baseInputPath;
    final OutputPath baseOutputPath;

    BaseClass(InputPath inputPath) {
      this.baseInputPath = inputPath;
      this.baseOutputPath = new OutputPath("baseOutput");
    }
  }

  private static class DerivedClass extends BaseClass {
    private static final Optional<String> STRING = Optional.empty();
    private final ImmutableList<InputPath> inputs;
    private final int value = 1;
    private final long something = 2;
    private final boolean enabled = true;
    private final OutputData outputData = new OutputData();

    DerivedClass(InputPath baseInputPath, ImmutableList<InputPath> inputs) {
      super(baseInputPath);
      this.inputs = inputs;
    }
  }

  @Test
  public void testDerivedClass() throws Exception {
    BuildTarget target1 = BuildTarget.of(Paths.get("some1"), "//some1", "name");
    BuildTarget target2 = BuildTarget.of(Paths.get("some2"), "//some2", "name");
    BuildTarget target3 = BuildTarget.of(Paths.get("some3"), "//some3", "name");

    BuildRule rule1 = new FakeBuildRule(target1, ImmutableSortedSet.of());
    BuildRule rule2 = new FakeBuildRule(target2, ImmutableSortedSet.of());
    BuildRule rule3 = new FakeBuildRule(target3, ImmutableSortedSet.of());

    BuildTargetSourcePath targetSourcePath1 =
        new ExplicitBuildTargetSourcePath(target1, Paths.get("path"));
    BuildTargetSourcePath targetSourcePath2 =
        new ExplicitBuildTargetSourcePath(target2, Paths.get("path"));
    BuildTargetSourcePath targetSourcePath3 =
        new ExplicitBuildTargetSourcePath(target3, Paths.get("path"));

    InputPath targetInputPath1 = new InputPath(targetSourcePath1);
    InputPath targetInputPath2 = new InputPath(targetSourcePath2);
    InputPath targetInputPath3 = new InputPath(targetSourcePath3);

    PathSourcePath pathSourcePath = new PathSourcePath(filesystem, Paths.get("path"));
    InputPath pathInputPath = new InputPath(pathSourcePath);

    DerivedClass buildable =
        new DerivedClass(
            targetInputPath1, ImmutableList.of(targetInputPath2, targetInputPath3, pathInputPath));
    ClassInfo<DerivedClass> classInfo = DefaultClassInfoFactory.forBuildable(buildable);
    assertEquals("derived_class", classInfo.getType());

    expect(ruleKeyObjectSink.setReflectively("BASE_NAME", "BaseClass"))
        .andReturn(ruleKeyObjectSink);
    expect(ruleKeyObjectSink.setReflectively("baseInputPath", targetSourcePath1))
        .andReturn(ruleKeyObjectSink);
    expect(ruleKeyObjectSink.setReflectively("baseOutputPath", "baseOutput"))
        .andReturn(ruleKeyObjectSink);
    expect(ruleKeyObjectSink.setReflectively("STRING", Optional.empty()))
        .andReturn(ruleKeyObjectSink);
    expect(
            ruleKeyObjectSink.setReflectively(
                "inputs", ImmutableList.of(targetSourcePath2, targetSourcePath3, pathSourcePath)))
        .andReturn(ruleKeyObjectSink);
    expect(ruleKeyObjectSink.setReflectively("value", 1)).andReturn(ruleKeyObjectSink);
    expect(ruleKeyObjectSink.setReflectively("something", 2l)).andReturn(ruleKeyObjectSink);
    expect(ruleKeyObjectSink.setReflectively("enabled", true)).andReturn(ruleKeyObjectSink);
    expect(ruleKeyObjectSink.setReflectively("outputData", "")).andReturn(ruleKeyObjectSink);

    replay(ruleKeyObjectSink);
    classInfo.appendToRuleKey(buildable, ruleKeyObjectSink);
    verify(ruleKeyObjectSink);

    expect(inputRuleResolver.resolve(targetInputPath1)).andReturn(Optional.of(rule1));
    expect(inputRuleResolver.resolve(targetInputPath2)).andReturn(Optional.of(rule2));
    expect(inputRuleResolver.resolve(targetInputPath3)).andReturn(Optional.of(rule3));
    expect(inputRuleResolver.resolve(pathInputPath)).andReturn(Optional.empty());

    buildRuleConsumer.accept(rule1);
    buildRuleConsumer.accept(rule2);
    buildRuleConsumer.accept(rule3);

    replay(inputRuleResolver, buildRuleConsumer);
    classInfo.computeDeps(buildable, inputRuleResolver, buildRuleConsumer);
    verify(inputRuleResolver, buildRuleConsumer);

    outputDataConsumer.accept("outputData", buildable.outputData);

    replay(outputDataConsumer);
    classInfo.getOutputData(buildable, outputDataConsumer);
    verify(outputDataConsumer);

    outputConsumer.accept("baseOutputPath", buildable.baseOutputPath);

    replay(outputConsumer);
    classInfo.getOutputs(buildable, outputConsumer);
    verify(outputConsumer);
  }

  @Test(expected = Exception.class)
  public void testLambdaBuildable() {
    try {
      DefaultClassInfoFactory.forBuildable(
          (eventDispatcher,
              filesystem,
              inputPathResolver,
              inputDataRetriever,
              outputPathResolver,
              buildCellPathFactory) -> null);
    } catch (Exception e) {
      assertThat(e.getMessage(), Matchers.containsString("cannot be synthetic"));
      assertThat(e.getMessage(), Matchers.containsString("DefaultClassInfoTest"));
      throw e;
    }
  }

  @Test(expected = Exception.class)
  public void testAnonymousBuildable() {
    try {
      DefaultClassInfoFactory.forBuildable(new NoOpBuildable() {});
    } catch (Exception e) {
      assertThat(e.getMessage(), Matchers.containsString("cannot be anonymous classes"));
      assertThat(e.getMessage(), Matchers.containsString("DefaultClassInfoTest"));
      throw e;
    }
  }

  @Test(expected = Exception.class)
  public void testLocalBuildable() {
    try {
      class LocalBuildable extends NoOpBuildable {}
      DefaultClassInfoFactory.forBuildable(new LocalBuildable());
    } catch (Exception e) {
      assertThat(e.getMessage(), Matchers.containsString("cannot be local classes"));
      assertThat(e.getMessage(), Matchers.containsString("LocalBuildable"));
      throw e;
    }
  }

  class NonStaticInnerBuildable extends NoOpBuildable {}

  @Test(expected = Exception.class)
  public void testNonStaticInner() {
    try {
      DefaultClassInfoFactory.forBuildable(new NonStaticInnerBuildable());
    } catch (Exception e) {
      assertThat(e.getMessage(), Matchers.containsString("cannot be inner non-static classes"));
      assertThat(e.getMessage(), Matchers.containsString("NonStaticInnerBuildable"));
      throw e;
    }
  }

  static class NonFinalFieldBuildable extends NoOpBuildable {
    int value = 0;
  }

  @Test(expected = Exception.class)
  public void testNonFinalField() {
    try {
      DefaultClassInfoFactory.forBuildable(new NonFinalFieldBuildable());
    } catch (Exception e) {
      assertThat(
          e.getMessage(), Matchers.containsString("must be final (NonFinalFieldBuildable.value)"));
      assertThat(e.getMessage(), Matchers.containsString("NonFinalFieldBuildable"));
      throw e;
    }
  }

  static class NonFinalStaticFieldBuildable extends NoOpBuildable {
    static int value = 0;
  }

  @Test(expected = Exception.class)
  public void testNonFinalStaticField() {
    try {
      DefaultClassInfoFactory.forBuildable(new NonFinalStaticFieldBuildable());
    } catch (Exception e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString("must be final (NonFinalStaticFieldBuildable.value)"));
      assertThat(e.getMessage(), Matchers.containsString("NonFinalStaticFieldBuildable"));
      throw e;
    }
  }

  static class BadBase extends NoOpBuildable {
    int value = 0;
  }

  static class DerivedFromBadBased extends BadBase {}

  @Test(expected = Exception.class)
  public void testBadBase() {
    try {
      DefaultClassInfoFactory.forBuildable(new DerivedFromBadBased());
    } catch (Exception e) {
      assertThat(e.getMessage(), Matchers.containsString("must be final (BadBase.value)"));
      assertThat(e.getMessage(), Matchers.containsString("DerivedFromBadBased"));
      throw e;
    }
  }

  @Test
  public void testSimpleModernBuildRule() {
    // Just tests that we can construct a class info from a "direct" ModernBuildRule.
    DefaultClassInfoFactory.forBuildable(
        new NoOpModernBuildRule(
            BuildTargetFactory.newInstance("//some:target"),
            new FakeProjectFilesystem(),
            new SourcePathRuleFinder(
                new SingleThreadedBuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()))));
  }

  static class NoOpModernBuildRule extends ModernBuildRule<NoOpModernBuildRule>
      implements Buildable {
    NoOpModernBuildRule(
        BuildTarget buildTarget, ProjectFilesystem filesystem, SourcePathRuleFinder finder) {
      super(buildTarget, filesystem, finder, NoOpModernBuildRule.class);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        EventDispatcher eventDispatcher,
        ProjectFilesystem filesystem,
        InputPathResolver inputPathResolver,
        InputDataRetriever inputDataRetriever,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of();
    }
  }
}
