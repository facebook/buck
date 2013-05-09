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
import static org.junit.Assert.fail;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

public class ReadKeystorePropertiesAndSignApkStepTest {

  @Test
  public void testGetDescription() {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context, projectFilesystem);

    ReadKeystorePropertiesAndSignApkStep signApk = new ReadKeystorePropertiesAndSignApkStep(
        "src/com/facebook/orca/keystore.properties",
        BIN_DIR + "/src/com/facebook/orca/orca_unsigned.apk",
        BIN_DIR + "/src/com/facebook/orca/orca_signed.apk",
        projectFilesystem);

    assertEquals("getDescription() should be able to be called before execute()",
        "sign " + BIN_DIR + "/src/com/facebook/orca/orca_unsigned.apk using the values in " +
        "src/com/facebook/orca/keystore.properties",
        signApk.getDescription(context));
    verify(context, projectFilesystem);
  }

  @Test
  public void testSetup() throws IOException {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    String pathToPropertiesFile = "src/com/facebook/orca/keystore.properties";
    Properties properties = new Properties();
    String alias = "androiddebugkey";
    properties.put("key.store", "debug.keystore");
    properties.put("key.alias", alias);
    properties.put("key.store.password", "android");
    properties.put("key.alias.password", "diordna");
    expect(projectFilesystem.readPropertiesFile(pathToPropertiesFile)).andReturn(properties);

    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context, projectFilesystem);

    String outputPath = BIN_DIR + "/src/com/facebook/orca/orca_signed.apk";
    String unsignedApkPath = BIN_DIR + "/src/com/facebook/orca/orca_unsigned.apk";
    ReadKeystorePropertiesAndSignApkStep readKeystorePropertiesAndSignCommand =
        new ReadKeystorePropertiesAndSignApkStep(
          pathToPropertiesFile,
          unsignedApkPath,
          outputPath,
            projectFilesystem);
    readKeystorePropertiesAndSignCommand.setup(context);
    SignApkStep signApkCommand = readKeystorePropertiesAndSignCommand.getSignApkCommand();

    assertEquals(outputPath, signApkCommand.getOutputPath());
    assertEquals(unsignedApkPath, signApkCommand.getUnsignedApk());
    assertEquals("The key.store property in the properties file should be resolved relative to" +
    		"the path of the properties file that defined it.",
        "src/com/facebook/orca/debug.keystore", signApkCommand.getKeystore());
    assertEquals("android", signApkCommand.getStorepass());
    assertEquals("diordna", signApkCommand.getKeypass());
    assertEquals(alias, signApkCommand.getAlias());

    assertEquals(
        ImmutableList.of("jarsigner",
            "-sigalg", "MD5withRSA",
            "-digestalg", "SHA1",
            "-keystore", "src/com/facebook/orca/debug.keystore",
            "-storepass", "android",
            "-keypass", "diordna",
            "-signedjar", outputPath,
            unsignedApkPath,
            alias),
        readKeystorePropertiesAndSignCommand.getShellCommand(context));

    verify(context, projectFilesystem);
  }

  @Test
  public void testSetupThrowsIOException() throws IOException {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    String pathToPropertiesFile = "src/com/facebook/orca/keystore.properties";
    Properties properties = new Properties();
    properties.put("key.store", "debug.keystore");
    properties.put("key.alias", "androiddebugkey");
    properties.put("key.store.password", "android");
    expect(projectFilesystem.readPropertiesFile(pathToPropertiesFile)).andReturn(properties);

    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context, projectFilesystem);

    ReadKeystorePropertiesAndSignApkStep readKeystorePropertiesAndSignCommand =
        new ReadKeystorePropertiesAndSignApkStep(
          pathToPropertiesFile,
          BIN_DIR + "/src/com/facebook/orca/orca_unsigned.apk",
          BIN_DIR + "/src/com/facebook/orca/orca_signed.apk",
            projectFilesystem);
    try {
      readKeystorePropertiesAndSignCommand.setup(context);
      fail("setup() should throw IOException");
    } catch (HumanReadableException e) {
      assertEquals(
          "Exception should have an error message that is useful to the user.",
          "properties file src/com/facebook/orca/keystore.properties did not contain a value for " +
          "the property key.alias.password",
          e.getHumanReadableErrorMessage());
    }
    verify(context, projectFilesystem);
  }

  @Test
  public void testShortName() throws IOException {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    ReadKeystorePropertiesAndSignApkStep readKeystorePropertiesAndSignCommand =
        new ReadKeystorePropertiesAndSignApkStep(
            "src/com/facebook/orca/keystore.properties",
            BIN_DIR + "/src/com/facebook/orca/orca_unsigned.apk",
            BIN_DIR + "/src/com/facebook/orca/orca_signed.apk",
            projectFilesystem);
    ExecutionContext context = createMock(ExecutionContext.class);

    replay(context, projectFilesystem);
    assertEquals("sign apk", readKeystorePropertiesAndSignCommand.getShortName(context));
    verify(context, projectFilesystem);
  }
}
