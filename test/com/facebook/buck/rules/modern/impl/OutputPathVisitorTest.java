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

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class OutputPathVisitorTest extends AbstractValueVisitorTest {
  private final OutputPath output0 = new OutputPath("some/path");
  private final OutputPath output1 = new OutputPath("other/path");

  private ImmutableList<OutputPath> getOutputs(Buildable value) {
    ImmutableList.Builder<OutputPath> builder = ImmutableList.builder();
    DefaultClassInfoFactory.forInstance(value).getOutputs(value, builder::add);
    return builder.build();
  }

  @Override
  @Test
  public void outputPath() {
    WithOutputPath value = new WithOutputPath();
    MoreAsserts.assertIterablesEquals(ImmutableList.of(value.output), getOutputs(value));
  }

  @Override
  @Test
  public void sourcePath() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithSourcePath()));
  }

  @Override
  @Test
  public void set() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithSet()));
  }

  @Override
  @Test
  public void addsToRuleKey() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithAddsToRuleKey()));
  }

  static class WithOutputPathList implements FakeBuildable {
    @AddToRuleKey private final ImmutableList<OutputPath> outputs;

    WithOutputPathList(ImmutableList<OutputPath> outputs) {
      this.outputs = outputs;
    }
  }

  @Override
  @Test
  public void list() {
    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(output0, output1),
        getOutputs(new WithOutputPathList(ImmutableList.of(output0, output1))));
  }

  @Override
  @Test
  public void optional() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithOptional()));
  }

  @Override
  @Test
  public void simple() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new Simple()));
  }

  @Override
  @Test
  public void superClass() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new Derived()));
  }

  @Override
  @Test
  public void empty() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new Empty()));
  }

  @Override
  @Test
  public void complex() {
    Complex value = new Complex();
    MoreAsserts.assertIterablesEquals(
        ImmutableList.builder().addAll(value.outputs).add(value.otherOutput).build(),
        getOutputs(value));
  }

  @Test
  @Override
  public void buildTarget() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithBuildTarget()));
  }
}
