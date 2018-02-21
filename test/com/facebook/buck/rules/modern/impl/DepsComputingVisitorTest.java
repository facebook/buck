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

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.InputRuleResolver;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.Test;

public class DepsComputingVisitorTest extends AbstractValueVisitorTest {
  private Consumer<BuildRule> depsBuilder = createStrictMock(Consumer.class);
  private InputRuleResolver inputRuleResolver = createStrictMock(InputRuleResolver.class);

  private void apply(Buildable value) {
    replay(depsBuilder, inputRuleResolver);
    DefaultClassInfoFactory.forInstance(value).computeDeps(value, inputRuleResolver, depsBuilder);
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
    apply(new Derived());
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
}
