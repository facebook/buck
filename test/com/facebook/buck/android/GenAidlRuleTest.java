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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Unit test for {@link com.facebook.buck.android.GenAidlRule}.
 */
public class GenAidlRuleTest {

  @Test
  public void testSimpleGenAidlRule() throws IOException {
    BuildContext context = null;

    String pathToAidl = "java/com/example/base/IWhateverService.aidl";
    String importPath = "java/com/example/base/";

    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    GenAidlRule genAidlRule = ruleResolver.buildAndAddToIndex(
        GenAidlRule.newGenAidlRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example/base:IWhateverService"))
        .setAidl(pathToAidl)
        .setImportPath(importPath));

    assertEquals(BuildRuleType.GEN_AIDL, genAidlRule.getType());
    assertTrue(genAidlRule.isAndroidRule());

    assertEquals(ImmutableList.of(pathToAidl), genAidlRule.getInputsToCompareToOutput());

    List<Step> steps = genAidlRule.buildInternal(context);

    final String pathToAidlExecutable = "/usr/local/bin/aidl";
    final String pathToFrameworkAidl = "/home/root/android/platforms/android-16/framework.aidl";
    AndroidPlatformTarget androidPlatformTarget = createMock(AndroidPlatformTarget.class);
    File aidlExecutable = createMock(File.class);
    expect(aidlExecutable.getAbsolutePath()).andReturn(pathToAidlExecutable);
    expect(androidPlatformTarget.getAidlExecutable()).andReturn(aidlExecutable);
    File frameworkIdlFile = createMock(File.class);
    expect(frameworkIdlFile.getAbsolutePath()).andReturn(pathToFrameworkAidl);
    expect(androidPlatformTarget.getAndroidFrameworkIdlFile()).andReturn(frameworkIdlFile);

    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.getAndroidPlatformTarget()).andReturn(androidPlatformTarget);
    expect(executionContext.getProjectFilesystem()).andReturn(
        new ProjectFilesystem(new File(".")) {
          @Override
          public Function<String, String> getPathRelativizer() {
            return Functions.identity();
          }
        }
    );
    replay(androidPlatformTarget,
        aidlExecutable,
        frameworkIdlFile,
        executionContext);

    String outputDirectory = String.format("%s/%s", BuckConstant.GEN_DIR, importPath);
    MkdirStep mkdirStep = (MkdirStep) steps.get(0);
    assertEquals("gen_aidl() should make a directory at " + outputDirectory,
        mkdirStep.getPath(executionContext),
        outputDirectory);

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

    assertEquals(2, steps.size());

    verify(androidPlatformTarget,
        aidlExecutable,
        frameworkIdlFile,
        executionContext);
  }
}
