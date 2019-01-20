/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android.exopackage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.InstallException;
import com.facebook.buck.android.TestDevice;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.testutil.TestConsole;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class RealAndroidDeviceTest {
  private TestDevice createDeviceForShellCommandTest(String output) {
    return new TestDevice() {
      @Override
      public void executeShellCommand(
          String cmd, IShellOutputReceiver receiver, long timeout, TimeUnit timeoutUnit) {
        byte[] outputBytes = output.getBytes(StandardCharsets.UTF_8);
        receiver.addOutput(outputBytes, 0, outputBytes.length);
        receiver.flush();
      }
    };
  }

  private static RealAndroidDevice createAndroidDevice(IDevice device) {
    return new RealAndroidDevice(
        BuckEventBusForTests.newInstance(),
        device,
        TestConsole.createNullConsole(),
        null,
        -1,
        ImmutableList.of());
  }

  /** Verify that successful installation on device results in true. */
  @Test
  public void testSuccessfulDeviceInstall() {
    File apk = new File("/some/file.apk");
    AtomicReference<String> apkPath = new AtomicReference<>();

    TestDevice device =
        new TestDevice() {
          @Override
          public void installPackage(String s, boolean b, String... strings) {
            apkPath.set(s);
          }
        };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");

    assertTrue(createAndroidDevice(device).installApkOnDevice(apk, false, false, false));
    assertEquals(apk.getAbsolutePath(), apkPath.get());
  }

  /** Also make sure we're not erroneously parsing "Exception" and "Error". */
  @Test
  public void testDeviceStartActivitySuccess() {
    TestDevice device =
        createDeviceForShellCommandTest(
            "Starting: Intent { cmp=com.example.ExceptionErrorActivity }\r\n");
    assertNull(createAndroidDevice(device).deviceStartActivity("com.foo/.Activity", false));
  }

  @Test
  public void testDeviceStartActivityAmDoesntExist() {
    TestDevice device = createDeviceForShellCommandTest("sh: am: not found\r\n");
    assertNotNull(createAndroidDevice(device).deviceStartActivity("com.foo/.Activity", false));
  }

  @Test
  public void testDeviceStartActivityActivityDoesntExist() {
    String errorLine = "Error: Activity class {com.foo/.Activiqy} does not exist.\r\n";
    TestDevice device =
        createDeviceForShellCommandTest(
            "Starting: Intent { cmp=com.foo/.Activiqy }\r\n" + "Error type 3\r\n" + errorLine);
    assertEquals(
        errorLine.trim(),
        createAndroidDevice(device).deviceStartActivity("com.foo/.Activiy", false).trim());
  }

  @Test
  public void testDeviceStartActivityException() {
    String errorLine =
        "java.lang.SecurityException: Permission Denial: "
            + "starting Intent { flg=0x10000000 cmp=com.foo/.Activity } from null "
            + "(pid=27581, uid=2000) not exported from uid 10002\r\n";
    TestDevice device =
        createDeviceForShellCommandTest(
            "Starting: Intent { cmp=com.foo/.Activity }\r\n"
                + errorLine
                + "  at android.os.Parcel.readException(Parcel.java:1425)\r\n"
                + "  at android.os.Parcel.readException(Parcel.java:1379)\r\n"
                +
                // (...)
                "  at dalvik.system.NativeStart.main(Native Method)\r\n");
    assertEquals(
        errorLine.trim(),
        createAndroidDevice(device).deviceStartActivity("com.foo/.Activity", false).trim());
  }

  /** Verify that if failure reason is returned, installation is marked as failed. */
  @Test
  public void testFailedDeviceInstallWithReason() {
    File apk = new File("/some/file.apk");
    TestDevice device =
        new TestDevice() {
          @Override
          public void installPackage(String s, boolean b, String... strings)
              throws InstallException {
            throw new InstallException("[SOME REASON]");
          }
        };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");
    assertFalse(createAndroidDevice(device).installApkOnDevice(apk, false, false, false));
  }

  /** Verify that if exception is thrown during installation, installation is marked as failed. */
  @Test
  public void testFailedDeviceInstallWithException() {
    File apk = new File("/some/file.apk");

    TestDevice device =
        new TestDevice() {
          @Override
          public void installPackage(String s, boolean b, String... strings)
              throws InstallException {
            throw new InstallException("Failed to install on test device.", null);
          }
        };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");
    assertFalse(createAndroidDevice(device).installApkOnDevice(apk, false, false, false));
  }

  @Test
  public void testDeviceStartActivityWaitForDebugger() {
    AtomicReference<String> runDeviceCommand = new AtomicReference<>();
    TestDevice device =
        new TestDevice() {
          @Override
          public void executeShellCommand(
              String command,
              IShellOutputReceiver receiver,
              long maxTimeToOutputResponse,
              TimeUnit maxTimeUnits) {
            runDeviceCommand.set(command);
          }
        };
    assertNull(createAndroidDevice(device).deviceStartActivity("com.foo/.Activity", true));
    assertTrue(runDeviceCommand.get().contains(" -D"));
  }

  @Test
  public void testDeviceStartActivityDoNotWaitForDebugger() {
    AtomicReference<String> runDeviceCommand = new AtomicReference<>();
    TestDevice device =
        new TestDevice() {
          @Override
          public void executeShellCommand(
              String command,
              IShellOutputReceiver receiver,
              long maxTimeToOutputResponse,
              TimeUnit maxTimeUnits) {
            runDeviceCommand.set(command);
          }
        };
    assertNull(createAndroidDevice(device).deviceStartActivity("com.foo/.Activity", false));
    assertFalse(runDeviceCommand.get().contains(" -D"));
  }

  @Test
  public void testDeviceBroadcast() throws Exception {
    AtomicReference<String> runDeviceCommand = new AtomicReference<>();
    TestDevice device =
        new TestDevice() {
          @Override
          public void executeShellCommand(String command, IShellOutputReceiver receiver) {
            runDeviceCommand.set(command);
            receiver.addOutput(":0".getBytes(), 0, 2);
          }
        };
    createAndroidDevice(device)
        .sendBroadcast("com.foo.ACTION", ImmutableMap.of("extra1", "value1"));
    String command = runDeviceCommand.get();
    assertTrue(command.contains("am broadcast"));
    assertTrue(command.contains("com.foo.ACTION"));
    assertTrue(command.contains("--es extra1"));
    assertTrue(command.contains("value1"));
  }
}
