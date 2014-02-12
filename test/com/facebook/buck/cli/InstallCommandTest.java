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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.InstallException;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

public class InstallCommandTest {

  private InstallCommand installCommand;

  @Before
  public void setUp() {
    installCommand = createInstallCommand();
  }

  private TestDevice createDeviceForShellCommandTest(final String output) {
    return new TestDevice() {
      @Override
      public void executeShellCommand(String cmd, IShellOutputReceiver receiver, int timeout) {
        byte[] outputBytes = output.getBytes();
        receiver.addOutput(outputBytes, 0, outputBytes.length);
        receiver.flush();
      }
    };
  }

  private InstallCommand createInstallCommand() {
    Console console = new TestConsole();
    ProjectFilesystem filesystem = new ProjectFilesystem(new File("."));
    AndroidDirectoryResolver androidDirectoryResolver = new FakeAndroidDirectoryResolver();
    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.getDefault();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    return new InstallCommand(new CommandRunnerParams(
        console,
        filesystem,
        androidDirectoryResolver,
        buildRuleTypes,
        new InstanceArtifactCacheFactory(artifactCache),
        eventBus,
        BuckTestConstant.PYTHON_INTERPRETER,
        Platform.detect()));
  }


  /**
   * Verify that successful installation on device results in true.
   */
  @Test
  public void testSuccessfulDeviceInstall() {
    File apk = new File("/some/file.apk");
    final AtomicReference<String> apkPath = new AtomicReference<>();

    TestDevice device = new TestDevice() {
      @Override
      public String installPackage(String s, boolean b, String... strings) throws InstallException {
        apkPath.set(s);
        return null;
      }
    };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");

    assertTrue(installCommand.installApkOnDevice(device, apk, false));
    assertEquals(apk.getAbsolutePath(), apkPath.get());
  }

  /**
   * Also make sure we're not erroneously parsing "Exception" and "Error".
   */
  @Test
  public void testDeviceStartActivitySuccess() {
    TestDevice device = createDeviceForShellCommandTest(
        "Starting: Intent { cmp=com.example.ExceptionErrorActivity }\r\n");
    assertNull(installCommand.deviceStartActivity(device, "com.foo/.Activity"));
  }

  @Test
  public void testDeviceStartActivityAmDoesntExist() {
    TestDevice device = createDeviceForShellCommandTest("sh: am: not found\r\n");
    assertNotNull(installCommand.deviceStartActivity(device, "com.foo/.Activity"));
  }

  @Test
  public void testDeviceStartActivityActivityDoesntExist() {
    String errorLine = "Error: Activity class {com.foo/.Activiqy} does not exist.\r\n";
    TestDevice device = createDeviceForShellCommandTest(
         "Starting: Intent { cmp=com.foo/.Activiqy }\r\n" +
         "Error type 3\r\n" +
         errorLine);
    assertEquals(
        errorLine.trim(),
        installCommand.deviceStartActivity(device, "com.foo/.Activiy").trim());
  }

  @Test
  public void testDeviceStartActivityException() {
    String errorLine = "java.lang.SecurityException: Permission Denial: " +
        "starting Intent { flg=0x10000000 cmp=com.foo/.Activity } from null " +
        "(pid=27581, uid=2000) not exported from uid 10002\r\n";
    TestDevice device = createDeviceForShellCommandTest(
        "Starting: Intent { cmp=com.foo/.Activity }\r\n" +
        errorLine +
         "  at android.os.Parcel.readException(Parcel.java:1425)\r\n" +
         "  at android.os.Parcel.readException(Parcel.java:1379)\r\n" +
        // (...)
        "  at dalvik.system.NativeStart.main(Native Method)\r\n");
    assertEquals(
        errorLine.trim(),
        installCommand.deviceStartActivity(device, "com.foo/.Activity").trim());
  }

  /**
   * Verify that if failure reason is returned, installation is marked as failed.
   */
  @Test
  public void testFailedDeviceInstallWithReason() {
    File apk = new File("/some/file.apk");
    TestDevice device = new TestDevice() {
      @Override
      public String installPackage(String s, boolean b, String... strings) throws InstallException {
        return "[SOME_REASON]";
      }
    };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");
    assertFalse(installCommand.installApkOnDevice(device, apk, false));
  }

  /**
   * Verify that if exception is thrown during installation, installation is marked as failed.
   */
  @Test
  public void testFailedDeviceInstallWithException() {
    File apk = new File("/some/file.apk");

    TestDevice device = new TestDevice() {
      @Override
      public String installPackage(String s, boolean b, String... strings) throws InstallException {
        throw new InstallException("Failed to install on test device.", null);
      }
    };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");
    assertFalse(installCommand.installApkOnDevice(device, apk, false));
  }
}
