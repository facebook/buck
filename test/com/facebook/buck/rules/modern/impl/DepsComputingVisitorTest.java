/*
 * Copyright 2018-present Facebook, Inc.
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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HasCustomDepsLogic;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.function.Consumer;
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
  public void addsToRuleKey() {
    WithAddsToRuleKey value = new WithAddsToRuleKey();
    expect(inputRuleResolver.resolve(FakeSourcePath.of(rootFilesystem, "appendable.path")))
        .andReturn(Optional.empty())
        .times(3);
    apply(value);
  }

  @Override
  @Test
  public void pattern() throws Exception {
    apply(new WithPattern());
  }

  @Override
  @Test
  public void anEnum() throws Exception {
    apply(new WithEnum());
  }

  @Override
  @Test
  public void nonHashableSourcePathContainer() throws Exception {
    WithNonHashableSourcePathContainer value = new WithNonHashableSourcePathContainer();
    expect(inputRuleResolver.resolve(value.container.getSourcePath())).andReturn(Optional.empty());
    apply(value);
  }

  @Override
  @Test
  public void sortedMap() throws Exception {
    WithSortedMap value = new WithSortedMap();
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty()).times(2);
    apply(value);
  }

  @Override
  @Test
  public void supplier() throws Exception {
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty());
    apply(new WithSupplier());
  }

  @Override
  @Test
  public void nullable() throws Exception {
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty());
    apply(new WithNullable());
  }

  @Override
  @Test
  public void either() throws Exception {
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty());
    apply(new WithEither());
  }

  @Override
  @Test
  public void excluded() throws Exception {
    apply(new WithExcluded());
  }

  @Override
  @Test
  public void immutables() throws Exception {
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty()).times(4);
    apply(new WithImmutables());
  }

  @Override
  @Test
  public void stringified() throws Exception {
    apply(new WithStringified());
  }

  @Override
  @Test
  public void wildcards() throws Exception {
    expect(inputRuleResolver.resolve(anyObject())).andReturn(Optional.empty());
    apply(new WithWildcards());
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
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
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
}
