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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit test for {@link com.facebook.buck.android.GenAidl}.
 */
public class GenAidlTest {

  @Test
  public void testSimpleGenAidlRule() throws IOException {
    BuildContext context = null;

    Path pathToAidl = Paths.get("java/com/example/base/IWhateverService.aidl");
    String importPath = "java/com/example/base/";

    BuildTarget target = BuildTargetFactory.newInstance("//java/com/example/base:IWhateverService");
    Buildable genAidlRule = new GenAidl(target, pathToAidl, importPath);

    GenAidlDescription description = new GenAidlDescription();
    assertEquals(GenAidlDescription.TYPE, description.getBuildRuleType());
    assertTrue(genAidlRule.getProperties().is(ANDROID));

    assertEquals(ImmutableList.of(pathToAidl), genAidlRule.getInputsToCompareToOutput());

    List<Step> steps = genAidlRule.getBuildSteps(context, new FakeBuildableContext());

    final String pathToAidlExecutable = "/usr/local/bin/aidl";
    final String pathToFrameworkAidl = "/home/root/android/platforms/android-16/framework.aidl";
    AndroidPlatformTarget androidPlatformTarget = createMock(AndroidPlatformTarget.class);
    expect(androidPlatformTarget.getAidlExecutable()).andReturn(Paths.get(pathToAidlExecutable));
    expect(androidPlatformTarget.getAndroidFrameworkIdlFile())
        .andReturn(Paths.get(pathToFrameworkAidl));

    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.getAndroidPlatformTarget()).andReturn(androidPlatformTarget);
    expect(executionContext.getProjectFilesystem()).andReturn(
        new ProjectFilesystem(new File(".")) {
          @Override
          public Path resolve(Path path) {
            return path;
          }
        })
        .times(2);
    replay(androidPlatformTarget, executionContext);

    Path outputDirectory = Paths.get(
        BuckConstant.BIN_DIR,
        "/java/com/example/base/.IWhateverService.aidl");
    MkdirStep mkdirStep = (MkdirStep) steps.get(0);
    assertEquals("gen_aidl() should make a directory at " + outputDirectory,
        outputDirectory,
        mkdirStep.getPath(executionContext));

    ShellStep aidlStep = (ShellStep) steps.get(1);
    assertEquals(
        "gen_aidl() should use the aidl binary to write .java files.",
        String.format("%s -b -p%s -I%s -o%s %s",
            pathToAidlExecutable,
            pathToFrameworkAidl,
            importPath,
            outputDirectory,
            pathToAidl),
        aidlStep.getDescription(executionContext));

    assertEquals(4, steps.size());

    verify(androidPlatformTarget, executionContext);
  }
}
