/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.testrunner;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellEnabledDevice;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.InstrumentationResultParser;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.facebook.buck.android.TestDevice;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/** Tests {@link InstrumentationTestRunner} */
public class InstrumentationTestRunnerTest {

  @Rule public final TemporaryPaths tmp = new TemporaryPaths();

  /** Just verifies the reflection is legit */
  @Test
  public void testSetTrimLinesHappyPath() throws Throwable {
    IShellEnabledDevice shellEnabledDevice = new TestDevice();
    RemoteAndroidTestRunner runner =
        new RemoteAndroidTestRunner("foobar", "blah", shellEnabledDevice);

    Field field = RemoteAndroidTestRunner.class.getDeclaredField("mParser");
    field.setAccessible(true);
    field.set(runner, new InstrumentationResultParser("fooBar", new ArrayList<ITestRunListener>()));

    InstrumentationTestRunner.setTrimLine(runner, true);
  }

  @Test
  public void testSyncExopackageDir() throws Exception {
    final Set<String> pushedFilePaths = new HashSet<>();
    IDevice device =
        new TestDevice() {
          @Override
          public void pushFile(String local, String remote) {
            pushedFilePaths.add(remote);
          }
        };
    Path rootFolder = tmp.newFolder("dummy_exo_contents");
    // Set up the files to be written, including the metadata file which specifies the base
    // directory
    Files.write(
        rootFolder.resolve("metadata.txt"), "/data/local/tmp/exopackage/com.example".getBytes());
    Path contents = rootFolder.resolve(Paths.get("a", "b", "c"));
    Files.createDirectories(contents);
    Files.write(contents.resolve("foo"), "Hello World".getBytes());

    // Perform the sync
    InstrumentationTestRunner.syncExopackageDir(rootFolder, device);

    // Now verify the results
    Set<String> expectedPaths =
        ImmutableSet.of(
            "/data/local/tmp/exopackage/com.example/metadata.txt",
            "/data/local/tmp/exopackage/com.example/a/b/c/foo");
    Assert.assertEquals(expectedPaths, pushedFilePaths);
  }
}
