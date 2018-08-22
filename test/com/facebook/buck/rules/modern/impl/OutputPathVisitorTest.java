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

import com.facebook.buck.core.rulekey.AddToRuleKey;
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
    DefaultClassInfoFactory.forInstance(value).visit(value, new OutputPathVisitor(builder::add));
    return builder.build();
  }

  @Override
  @Test
  public void outputPath() {
    WithOutputPath value = new WithOutputPath();
    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(value.output, value.publicOutput, value.publicAsOutputPath),
        getOutputs(value));
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
  public void sortedSet() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithSortedSet()));
  }

  @Override
  @Test
  public void addsToRuleKey() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithAddsToRuleKey()));
  }

  @Override
  @Test
  public void pattern() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithPattern()));
  }

  @Override
  @Test
  public void anEnum() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithEnum()));
  }

  @Override
  @Test
  public void nonHashableSourcePathContainer() throws Exception {
    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(), getOutputs(new WithNonHashableSourcePathContainer()));
  }

  @Override
  @Test
  public void map() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithMap()));
  }

  @Override
  @Test
  public void sortedMap() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithSortedMap()));
  }

  @Override
  @Test
  public void supplier() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithSupplier()));
  }

  @Override
  @Test
  public void nullable() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithNullable()));
  }

  @Override
  @Test
  public void either() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithEither()));
  }

  @Override
  @Test
  public void excluded() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithExcluded()));
  }

  @Override
  @Test
  public void immutables() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithImmutables()));
  }

  @Override
  @Test
  public void stringified() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithStringified()));
  }

  @Override
  @Test
  public void wildcards() throws Exception {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithWildcards()));
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
  public void optionalInt() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new WithOptionalInt()));
  }

  @Override
  @Test
  public void simple() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new Simple()));
  }

  @Override
  @Test
  public void superClass() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), getOutputs(new TwiceDerived()));
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
