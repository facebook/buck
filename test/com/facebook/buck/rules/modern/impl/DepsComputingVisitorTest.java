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

package com.facebook.buck.rules.modern.impl;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldDeps;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasCustomDepsLogic;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.CustomFieldDeps;
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.Test;

public class DepsComputingVisitorTest extends AbstractValueVisitorTest {
  private Consumer<BuildRule> depsBuilder = createStrictMock(Consumer.class);
  private InputRuleResolver inputRuleResolver = createStrictMock(InputRuleResolver.class);

  private void apply(Buildable value) {
    replay(depsBuilder, inputRuleResolver);
    DefaultClassInfoFactory.forInstance(value)
        .visit(value, new DepsComputingVisitor(inputRuleResolver, depsBuilder));
    verify(depsBuilder, inputRuleResolver);
  }

  @Override
  @Test
  public void outputPath() {
    apply(new WithOutputPath());
  }

  @Override
  @Test
  public void sourcePath() {
    WithSourcePath value = new WithSourcePath();
    FakeBuildRule rule = new FakeBuildRule("//some:target");
    expect(inputRuleResolver.resolve(value.path)).andReturn(Optional.of(rule));
    depsBuilder.accept(rule);
    apply(value);
  }

  @Override
  @Test
  public void set() {
    apply(new WithSet());
  }

  @Override
  @Test
  public void sortedSet() {
    apply(new WithSortedSet());
  }

  @Override
  @Test
  public void addsToRuleKey() {
    WithAddsToRuleKey value = new WithAddsToRuleKey();
    expect(inputRuleResolver.resolve(FakeSourcePath.of(rootFilesystem, "appendable.path")))
        .andReturn(Optional.empty())
        .times(3);
    apply(value);
  }

  @Override
  @Test
  public void pattern() {
    apply(new WithPattern());
  }

  @Override
  @Test
  public void anEnum() {
    apply(new WithEnum());
  }

  @Override
  @Test
  public void nonHashableSourcePathContainer() {
    WithNonHashableSourcePathContainer value = new WithNonHashableSourcePathContainer();
    expect(inputRuleResolver.resolve(value.container.getSourcePath())).andReturn(Optional.empty());
    apply(value);
  }

  @Override
  @Test
  public void outputLabel() throws Exception {
    apply(new WithOutputLabel());
  }

  @Override
  @Test
  public void map() {
    WithMap value = new WithMap();
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty()).times(2);
    apply(value);
  }

  @Override
  @Test
  public void sortedMap() {
    WithSortedMap value = new WithSortedMap();
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty()).times(2);
    apply(value);
  }

  @Override
  @Test
  public void supplier() {
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty());
    apply(new WithSupplier());
  }

  @Override
  @Test
  public void nullable() {
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty());
    apply(new WithNullable());
  }

  @Override
  @Test
  public void either() {
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty());
    apply(new WithEither());
  }

  @Override
  @Test
  public void excluded() {
    apply(new WithExcluded());
  }

  @Override
  @Test
  public void immutables() {
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty()).times(4);
    apply(new WithImmutables());
  }

  @Override
  @Test
  public void stringified() {
    apply(new WithStringified());
  }

  @Override
  @Test
  public void wildcards() {
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty());
    apply(new WithWildcards());
  }

  @Override
  @Test
  public void withExcludeFromRuleKey() {
    apply(new WithExcludeFromRuleKey());
  }

  static class WithSourcePathList implements FakeBuildable {
    @AddToRuleKey private final ImmutableList<SourcePath> inputs;

    WithSourcePathList(ImmutableList<SourcePath> inputs) {
      this.inputs = inputs;
    }
  }

  @Override
  @Test
  public void list() {
    PathSourcePath fakePath = FakeSourcePath.of("some/path");
    DefaultBuildTargetSourcePath targetPath =
        DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//some:target"));
    expect(inputRuleResolver.resolve(fakePath)).andReturn(Optional.empty());
    FakeBuildRule fakeRule = new FakeBuildRule("//some:target");
    expect(inputRuleResolver.resolve(targetPath)).andReturn(Optional.of(fakeRule));
    depsBuilder.accept(fakeRule);
    apply(new WithSourcePathList(ImmutableList.of(fakePath, targetPath)));
  }

  @Override
  @Test
  public void optional() {
    apply(new WithOptional());
  }

  @Override
  @Test
  public void frameworkPath() {
    WithFrameworkPath value = new WithFrameworkPath();
    FakeBuildRule rule = new FakeBuildRule("//some:target");
    expect(inputRuleResolver.resolve(value.sourcePath.getSourcePath().get()))
        .andReturn(Optional.of(rule));
    depsBuilder.accept(rule);
    apply(value);
  }

  @Override
  @Test
  public void simple() {
    apply(new Simple());
  }

  @Override
  @Test
  public void superClass() {
    apply(new TwiceDerived());
  }

  @Override
  @Test
  public void empty() {
    apply(new Empty());
  }

  @Override
  @Test
  public void complex() {
    Complex value = new Complex();
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty());
    expectLastCall().times(3);
    apply(value);
  }

  @Test
  @Override
  public void buildTarget() {
    apply(new WithBuildTarget());
  }

  @Test
  @Override
  public void buildTargetWithEmptyConfiguration() {
    apply(new WithBuildTargetWithEmptyConfiguration());
  }

  @Test
  @Override
  public void buildTargetWithConfigurationForConfigurationTargets() {
    apply(new WithBuildTargetWithConfigurationForConfigurationTargets());
  }

  private static final BuildRule otherRule =
      new FakeBuildRule(someBuildTarget.withFlavors(InternalFlavor.of("other")));

  private static class HasCustomDeps implements AddsToRuleKey, HasCustomDepsLogic {
    @AddToRuleKey
    private final SourcePath sourcePath = DefaultBuildTargetSourcePath.of(someBuildTarget);

    @Override
    public Stream<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
      return Stream.of(otherRule);
    }
  }

  private static class WithCustomDeps implements AddsToRuleKey {
    @AddToRuleKey private final HasCustomDeps hasCustomDeps = new HasCustomDeps();
  }

  @Test
  public void customDeps() {
    WithCustomDeps withCustomDeps = new WithCustomDeps();
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    InputRuleResolver ruleResolver =
        new InputRuleResolver() {
          @Override
          public Optional<BuildRule> resolve(SourcePath path) {
            throw new RuntimeException();
          }

          @Override
          public UnsafeInternals unsafe() {
            return () -> ruleFinder;
          }
        };
    ImmutableList.Builder<BuildRule> depsBuilder = ImmutableList.builder();
    DefaultClassInfoFactory.forInstance(withCustomDeps)
        .visit(withCustomDeps, new DepsComputingVisitor(ruleResolver, depsBuilder::add));
    assertEquals(depsBuilder.build(), ImmutableList.of(otherRule));
  }

  private static class CustomFieldDepsReturningOtherRule implements CustomFieldDeps<Object> {
    @Override
    public void getDeps(Object value, Consumer<BuildRule> consumer) {
      consumer.accept(otherRule);
    }
  }

  private static class ExcludedFromRuleKeyCustomDeps implements AddsToRuleKey {
    @ExcludeFromRuleKey(
        serialization = DefaultFieldSerialization.class,
        inputs = DefaultFieldInputs.class,
        deps = CustomFieldDepsReturningOtherRule.class)
    private final SourcePath sourcePath = FakeSourcePath.of("test");
  }

  private static class ExcludedFromRuleKeyDefaultDeps implements AddsToRuleKey {
    @ExcludeFromRuleKey(
        serialization = DefaultFieldSerialization.class,
        inputs = DefaultFieldInputs.class,
        deps = DefaultFieldDeps.class)
    private final SourcePath sourcePath = FakeSourcePath.of("test");
  }

  private static class ExcludedFromRuleKeyIgnoredDeps implements AddsToRuleKey {
    @ExcludeFromRuleKey(
        serialization = DefaultFieldSerialization.class,
        inputs = DefaultFieldInputs.class
        /*deps = IgnoredFieldDeps.class*/ ) // suppress warning by commenting out
    private final SourcePath sourcePath = FakeSourcePath.of("test");
  }

  private void checkComputedDepsForObject(
      AddsToRuleKey object,
      Function<SourcePath, Optional<BuildRule>> ruleResolverFunction,
      ImmutableList<BuildRule> expectedRules) {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    InputRuleResolver ruleResolver =
        new InputRuleResolver() {
          @Override
          public Optional<BuildRule> resolve(SourcePath path) {
            return ruleResolverFunction.apply(path);
          }

          @Override
          public UnsafeInternals unsafe() {
            return () -> ruleFinder;
          }
        };
    ImmutableList.Builder<BuildRule> depsBuilder = ImmutableList.builder();
    DefaultClassInfoFactory.forInstance(object)
        .visit(object, new DepsComputingVisitor(ruleResolver, depsBuilder::add));
    assertEquals(depsBuilder.build(), expectedRules);
  }

  @Test
  public void testExcludedFromRuleKeyCustomDepsField() {
    // We're not returning any rules via the rule resolver,
    // so if the code works properly, it must have called
    // the custom override point defined by CustomFieldDeps<T>
    checkComputedDepsForObject(
        new ExcludedFromRuleKeyCustomDeps(),
        (sourcePath -> Optional.empty()),
        ImmutableList.of(otherRule));
  }

  @Test
  public void testExcludedFromRuleKeyDefaultDepsField() {
    checkComputedDepsForObject(
        new ExcludedFromRuleKeyDefaultDeps(),
        (sourcePath -> Optional.of(otherRule)),
        ImmutableList.of(otherRule));
  }

  @Test
  public void testExcludedFromRuleKeyIgnoredDepsField() {
    checkComputedDepsForObject(
        new ExcludedFromRuleKeyIgnoredDeps(),
        (sourcePath -> Optional.of(otherRule)),
        ImmutableList.of());
  }
}
