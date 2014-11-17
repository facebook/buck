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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit test for {@link NdkLibrary}.
 */
public class NdkLibraryTest {

  @Test
  public void testSimpleNdkLibraryRule() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildContext context = null;

    String basePath = "java/src/com/facebook/base";
    NdkLibrary ndkLibrary =
        NdkLibraryBuilder.createNdkLibrary(
            BuildTargetFactory.newInstance(
                String.format("//%s:base", basePath)),
            pathResolver)
            .setNdkVersion("r8b")
            .addSrc(Paths.get(basePath + "/Application.mk"))
            .addSrc(Paths.get(basePath + "/main.cpp"))
            .addSrc(Paths.get(basePath + "/Android.mk"))
            .addFlag("flag1")
            .addFlag("flag2")
            .setIsAsset(true)
            .build();

    ruleResolver.addToIndex(ndkLibrary);

    assertEquals(NdkLibraryDescription.TYPE, ndkLibrary.getType());

    assertTrue(ndkLibrary.getProperties().is(ANDROID));
    assertTrue(ndkLibrary.isAsset());
    assertEquals(Paths.get(BuckConstant.GEN_DIR, basePath, "__libbase"),
        ndkLibrary.getLibraryPath());

    MoreAsserts.assertListEquals(
        ImmutableList.of(
            Paths.get(basePath + "/Android.mk"),
            Paths.get(basePath + "/Application.mk"),
            Paths.get(basePath + "/main.cpp")),
        ImmutableList.copyOf(ndkLibrary.getInputsToCompareToOutput()));

    List<Step> steps = ndkLibrary.getBuildSteps(context, new FakeBuildableContext());

    ExecutionContext executionContext = createMock(ExecutionContext.class);
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    Function<Path, Path> pathTransform = new Function<Path, Path>() {
      @Override
      public Path apply(Path pathRelativeTo) {
        return Paths.get("/foo/", pathRelativeTo.toString());
      }
    };
    expect(executionContext.getProjectFilesystem()).andReturn(projectFilesystem);
    expect(projectFilesystem.getAbsolutifier()).andReturn(pathTransform);
    Path binDir = Paths.get(BuckConstant.BIN_DIR, "java/src/com/facebook/base/__libbase/libs");
    expect(projectFilesystem.resolve(binDir)).andReturn(Paths.get("/foo/" + binDir));
    Path ndkDir = createMock(Path.class);
    AndroidPlatformTarget mockPlatformTarget = createMock(AndroidPlatformTarget.class);
    expect(mockPlatformTarget.getNdkDirectory()).andReturn(Optional.of(ndkDir));
    expect(executionContext.getAndroidPlatformTarget()).andReturn(mockPlatformTarget);
    Path ndkBuildDir = createMock(Path.class);
    expect(ndkDir.resolve("ndk-build")).andReturn(ndkBuildDir);
    expect(ndkBuildDir.toAbsolutePath()).andReturn(Paths.get("/ndk-r8b/ndk-build"));
    expect(executionContext.getVerbosity()).andReturn(Verbosity.STANDARD_INFORMATION);

    replay(executionContext, projectFilesystem, mockPlatformTarget, ndkDir, ndkBuildDir);
    MoreAsserts.assertShellCommands(
        "ndk_library() should invoke ndk-build on the given path with some -j value",
        ImmutableList.of(
            String.format(
              "/ndk-r8b/ndk-build -j %d -C %s flag1 flag2 " +
              "APP_PROJECT_PATH=/foo/%s/%s/%s/ APP_BUILD_SCRIPT=/foo/%s/Android.mk " +
              "NDK_OUT=/foo/%s/%s/%s/ " +
              "NDK_LIBS_OUT=/foo/%s/%s/%s/libs " +
              "host-echo-build-step=@# " +
              "--silent",
              Runtime.getRuntime().availableProcessors(),
              basePath,
              BuckConstant.BIN_DIR,
              basePath,
              "__libbase",
              basePath,
              BuckConstant.BIN_DIR,
              basePath,
              "__libbase",
              BuckConstant.BIN_DIR,
              basePath,
              "__libbase")
        ),
        steps.subList(0, 1),
        executionContext);
    verify(executionContext, projectFilesystem);
  }
}
