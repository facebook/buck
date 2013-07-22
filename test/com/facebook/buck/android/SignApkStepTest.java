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

import static com.facebook.buck.util.BuckConstant.BIN_DIR;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

public class SignApkStepTest {

  @Test
  public void testGetShellCommand() throws IOException {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    Properties properties = new Properties();
    properties.put("key.alias", "androiddebugkey");
    properties.put("key.store.password", "android");
    properties.put("key.alias.password", "diordna");
    expect(projectFilesystem.readPropertiesFile("src/com/facebook/orca/debug.keystore.properties"))
        .andReturn(properties);

    ExecutionContext context = createMock(ExecutionContext.class);
    expect(context.getProjectFilesystem()).andReturn(projectFilesystem);
    replay(projectFilesystem, context);

    String outputPath = BIN_DIR + "/src/com/facebook/orca/orca_signed.apk";
    String unsignedApkPath = BIN_DIR + "/src/com/facebook/orca/orca_unsigned.apk";

    SignApkStep signApkCommand = new SignApkStep(
        "src/com/facebook/orca/debug.keystore",
        "src/com/facebook/orca/debug.keystore.properties",
        unsignedApkPath,
        outputPath);

    assertEquals("jarsigner", signApkCommand.getShortName());

    assertEquals(
        ImmutableList.of("jarsigner",
            "-sigalg", "MD5withRSA",
            "-digestalg", "SHA1",
            "-keystore", "src/com/facebook/orca/debug.keystore",
            "-storepass", "android",
            "-keypass", "diordna",
            "-signedjar", outputPath,
            unsignedApkPath,
            "androiddebugkey"),
            signApkCommand.getShellCommand(context));

    verify(projectFilesystem, context);
  }
}
