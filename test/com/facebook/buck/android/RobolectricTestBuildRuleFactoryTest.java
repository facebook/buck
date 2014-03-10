/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.java.FakeJavaLibraryRule;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.testutil.IdentityPathAbsolutifier;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.easymock.EasyMock;
import org.junit.Test;

import java.util.Map;

public class RobolectricTestBuildRuleFactoryTest {

  @Test
  public void testAmendBuilder() throws NoSuchBuildTargetException {
    Map<BuildTarget, BuildRule> buildRuleIndex = Maps.newHashMap();
    BuildRuleResolver ruleResolver = new BuildRuleResolver(buildRuleIndex);

    // Set up mocks.
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.getAbsolutifier()).andReturn(
        IdentityPathAbsolutifier.getIdentityAbsolutifier());
    BuildTargetParser buildTargetParser = new BuildTargetParser(projectFilesystem) {
      @Override
      public BuildTarget parse(String buildTargetName, ParseContext parseContext)
          throws NoSuchBuildTargetException {
        return BuildTargetFactory.newInstance(buildTargetName);
      }
    };
    Map<String, ?> instance = ImmutableMap.of(
        "vm_args", ImmutableList.of("-Dbuck.robolectric_dir=javatests/com/facebook/base"),
        "source_under_test", ImmutableList.of("//java/com/facebook/base:base"));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//javatests/com/facebook/base:base");
    BuildFileTree buildFileTree = EasyMock.createMock(BuildFileTree.class);
    EasyMock.replay(projectFilesystem, buildFileTree);
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        instance,
        projectFilesystem,
        buildFileTree,
        buildTargetParser,
        buildTarget,
        new FakeRuleKeyBuilderFactory());

    // Create a builder using the factory.
    RobolectricTestBuildRuleFactory factory = new RobolectricTestBuildRuleFactory();
    RobolectricTestRule.Builder builder = factory
        .newBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(buildTarget);

    // Invoke the method under test.
    factory.amendBuilder(builder, params);

    // Create a build rule using the builder.
    BuildRule base = new FakeJavaLibraryRule(
        BuildRuleType.ANDROID_LIBRARY,
        buildTarget,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());
    buildRuleIndex.put(BuildTargetFactory.newInstance("//java/com/facebook/base:base"), base);
    RobolectricTestRule robolectricRule = (RobolectricTestRule) ruleResolver
        .buildAndAddToIndex(builder);

    // Verify the build rule built from the builder.
    assertEquals(ImmutableList.of("-Dbuck.robolectric_dir=javatests/com/facebook/base"),
        robolectricRule.getVmArgs());
    assertEquals(ImmutableSet.of(base), robolectricRule.getSourceUnderTest());
    EasyMock.verify(projectFilesystem, buildFileTree);
  }
}
