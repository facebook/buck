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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Unit test for {@link com.facebook.buck.android.NdkLibraryRule}.
 */
public class NdkLibraryRuleTest {

  @Test
  public void testSimpleNdkLibraryRule() throws IOException {
    BuildContext context = null;

    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    String basePath = "java/src/com/facebook/base";
    NdkLibraryRule ndkLibraryRule = NdkLibraryRule.newNdkLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance(
            String.format("//%s:base", basePath)))
        .addSrc(basePath + "/Application.mk")
        .addSrc(basePath + "/main.cpp")
        .addSrc(basePath + "/Android.mk")
        .addFlag("flag1")
        .addFlag("flag2")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .setArtifactCache(new NoopArtifactCache())
        .build(buildRuleIndex);
    buildRuleIndex.put(ndkLibraryRule.getFullyQualifiedName(), ndkLibraryRule);

    Assert.assertEquals(BuildRuleType.NDK_LIBRARY, ndkLibraryRule.getType());
    assertTrue(ndkLibraryRule.isAndroidRule());

    MoreAsserts.assertListEquals(
        ImmutableList.of(
            basePath + "/Android.mk",
            basePath + "/Application.mk",
            basePath + "/main.cpp"),
        ImmutableList.copyOf(ndkLibraryRule.getInputsToCompareToOutput(context)));

    List<Step> steps = ndkLibraryRule.buildInternal(context);

    ExecutionContext executionContext = createMock(ExecutionContext.class);
    File projectRoot = createMock(File.class);
    expect(executionContext.getProjectDirectoryRoot()).andReturn(projectRoot);
    expect(projectRoot.getAbsolutePath()).andReturn("/foo");
    File ndkDir = createMock(File.class);
    expect(executionContext.getNdkRoot()).andReturn(Optional.of(ndkDir));
    expect(ndkDir.getAbsolutePath()).andReturn("/ndk-r8b");

    replay(executionContext, projectRoot, ndkDir);
    MoreAsserts.assertShellCommands(
        "ndk_library() should invoke ndk-build on the given path with some -j value",
        ImmutableList.of(
            String.format(
              "/ndk-r8b/ndk-build -j %d -C %s/ flag1 flag2 " +
              "APP_PROJECT_PATH=/foo/%s/%s/ APP_BUILD_SCRIPT=/foo/%s/Android.mk " +
              "NDK_OUT=/foo/%s/%s/",
              Runtime.getRuntime().availableProcessors(),
              basePath,
              BuckConstant.GEN_DIR,
              basePath,
              basePath,
              BuckConstant.GEN_DIR,
              basePath)
        ),
        steps,
        executionContext);
    verify(executionContext, projectRoot);
  }
}
