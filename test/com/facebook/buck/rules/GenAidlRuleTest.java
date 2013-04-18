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

package com.facebook.buck.rules;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.shell.Command;
import com.facebook.buck.shell.ExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Unit test for {@link GenAidlRule}.
 */
public class GenAidlRuleTest {

  @Test
  public void testSimpleGenAidlRule() throws IOException {
    BuildContext context = null;
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    String pathToAidl = "java/com/example/base/IWhateverService.aidl";
    String importPath = "java/com/example/base/";

    GenAidlRule genAidlRule = GenAidlRule.newGenAidlRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example/base:IWhateverService"))
        .setAidl(pathToAidl)
        .setImportPath(importPath)
        .build(buildRuleIndex);

    assertEquals(BuildRuleType.GEN_AIDL, genAidlRule.getType());
    assertTrue(genAidlRule.isAndroidRule());

    assertEquals(ImmutableList.of(pathToAidl), genAidlRule.getInputsToCompareToOutput(context));

    List<Command> commands = genAidlRule.buildInternal(context);

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
    expect(executionContext.getAndroidPlatformTarget()).andReturn(
        Optional.of(androidPlatformTarget));
    replay(androidPlatformTarget,
        aidlExecutable,
        frameworkIdlFile,
        executionContext);

    String outputDirectory = String.format("%s/%s", BuckConstant.GEN_DIR, importPath);
    MoreAsserts.assertShellCommands(
        "gen_aidl() should make a directory and then use the aidl binary to write .java files.",
        ImmutableList.of(
            String.format("mkdir -p %s", outputDirectory),
            String.format("%s -b -p%s -I%s -o%s %s",
                pathToAidlExecutable,
                pathToFrameworkAidl,
                importPath,
                outputDirectory,
                pathToAidl)
        ),
        commands,
        executionContext);

    verify(androidPlatformTarget,
        aidlExecutable,
        frameworkIdlFile,
        executionContext);
  }
}
