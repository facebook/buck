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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildRuleFactoryParams;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.PrintStream;
import java.util.Map;

public class RobolectricTestBuildRuleFactoryTest {
  private static final ArtifactCache artifactCache = new NoopArtifactCache();

  @Test
  public void testAmendBuilder() throws NoSuchBuildTargetException {
    // Set up mocks.
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
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
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        instance,
        /* stdErr */ EasyMock.createMock(PrintStream.class),
        projectFilesystem,
        artifactCache,
        /* buildFiles */ null,
        buildTargetParser,
        buildTarget);
    EasyMock.replay(projectFilesystem);

    // Create a builder using the factory.
    RobolectricTestBuildRuleFactory factory = new RobolectricTestBuildRuleFactory();
    RobolectricTestRule.Builder builder = factory.newBuilder()
        .setBuildTarget(buildTarget)
        .setArtifactCache(artifactCache);

    // Invoke the method under test.
    factory.amendBuilder(builder, params);

    // Create a build rule using the builder.
    BuildRule base = new FakeJavaLibraryRule(
        BuildRuleType.ANDROID_LIBRARY,
        buildTarget,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());
    Map<String, BuildRule> buildRuleIndex = ImmutableMap.of("//java/com/facebook/base:base", base);
    RobolectricTestRule robolectricRule = builder.build(buildRuleIndex);

    // Verify the build rule built from the builder.
    assertEquals(ImmutableList.of("-Dbuck.robolectric_dir=javatests/com/facebook/base"),
        robolectricRule.getVmArgs());
    assertEquals(ImmutableSet.of(base), robolectricRule.getSourceUnderTest());
    EasyMock.verify(projectFilesystem);
  }
}
