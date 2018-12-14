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
package com.facebook.buck.rules.modern.builders;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.AbstractBuildRuleResolver;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.modern.annotations.CustomClassBehavior;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ModernBuildRuleRemoteExecutionHelperTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ModernBuildRuleRemoteExecutionHelper mbrHelper;
  private ProjectFilesystem filesystem;
  private SourcePathRuleFinder ruleFinder;

  @Before
  public void setUp() throws Exception {
    BuckEventBus eventBus = new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId("dontcare"));
    ruleFinder =
        new SourcePathRuleFinder(
            new AbstractBuildRuleResolver() {
              @Override
              public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
                return Optional.empty();
              }
            });

    filesystem = new FakeProjectFilesystem(tmp.getRoot());
    Cell root = new TestCellBuilder().setFilesystem(filesystem).build();
    mbrHelper =
        new ModernBuildRuleRemoteExecutionHelper(
            eventBus,
            new GrpcProtocol(),
            ruleFinder,
            root.getCellPathResolver(),
            root,
            ImmutableSet.of(Optional.empty()),
            tmp.getRoot(),
            path -> HashCode.fromInt(0));
  }

  public static class SimpleBuildable implements Buildable {
    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of();
    }
  }

  public static class GoodBuildable extends SimpleBuildable {
    @AddToRuleKey ImmutableList<SourcePath> paths = ImmutableList.of();
  }

  public static class ExcludedFieldBuildable extends SimpleBuildable {
    // Non-@AddToRuleKey fields should fail.
    final int value = 0;
  }

  public static class CustomFieldSerialization extends SimpleBuildable {
    @CustomFieldBehavior(BadFieldSerialization.class)
    final int value;

    public CustomFieldSerialization(int value) {
      this.value = value;
    }
  }

  static class BadFieldSerialization
      implements com.facebook.buck.rules.modern.CustomFieldSerialization<Integer> {

    @Override
    public <E extends Exception> void serialize(Integer value, ValueVisitor<E> serializer) {
      Verify.verify(value == 0);
    }

    @Override
    public <E extends Exception> Integer deserialize(ValueCreator<E> deserializer) {
      return null;
    }
  }

  @CustomClassBehavior(GoodSerialization.class)
  public static class CustomClassSerialization extends SimpleBuildable {
    final int value;

    public CustomClassSerialization(int value) {
      this.value = value;
    }
  }

  static class GoodSerialization
      implements com.facebook.buck.rules.modern.CustomClassSerialization<CustomClassSerialization> {
    @Override
    public <E extends Exception> void serialize(
        CustomClassSerialization instance, ValueVisitor<E> serializer) {
      Verify.verify(instance.value == 0);
    }

    @Override
    public <E extends Exception> CustomClassSerialization deserialize(
        ValueCreator<E> deserializer) {
      return null;
    }
  }

  public <T extends Buildable> ModernBuildRule<T> wrapAsRule(final T buildable) {
    return new ModernBuildRule<T>(
        BuildTargetFactory.newInstance("//:target"), filesystem, ruleFinder, buildable) {};
  }

  @Test
  public void testGoodRule() {
    assertTrue(mbrHelper.supportsRemoteExecution(wrapAsRule(new GoodBuildable())));
  }

  @Test
  public void testExcludedField() {
    assertFalse(mbrHelper.supportsRemoteExecution(wrapAsRule(new ExcludedFieldBuildable())));
  }

  @Test
  public void testGoodCustomFieldSerialization() {
    assertTrue(mbrHelper.supportsRemoteExecution(wrapAsRule(new CustomFieldSerialization(0))));
  }

  @Test
  public void testBadCustomFieldSerialization() {
    assertFalse(mbrHelper.supportsRemoteExecution(wrapAsRule(new CustomFieldSerialization(1))));
  }

  @Test
  public void testGoodCustomClassSerialization() {
    assertTrue(mbrHelper.supportsRemoteExecution(wrapAsRule(new CustomClassSerialization(0))));
  }

  @Test
  public void testBadCustomClassSerialization() {
    assertFalse(mbrHelper.supportsRemoteExecution(wrapAsRule(new CustomClassSerialization(1))));
  }
}
