/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.d;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that the autodetection in DBuckConfig works and that explicitly
 * configured values are respected.
 */
public class DBuckConfigTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testCompilerInPath() throws IOException {
    File yooserBeen = tmp.newFolder("yooser", "been");
    File dmd = new File(yooserBeen, "dmd");
    dmd.createNewFile();
    dmd.setExecutable(true);
    BuckConfig delegate = FakeBuckConfig.builder()
      .setEnvironment(ImmutableMap.of(
              "PATH", yooserBeen.getCanonicalPath()))
      .build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    assertEquals(dmd.getCanonicalPath(), toolPath(dBuckConfig.getDCompiler()));
  }

  @Test
  public void testCompilerNotInPath() throws IOException {
    File yooserBeen = tmp.newFolder("yooser", "been");
    String userBean = tmp.newFolder("user", "bean").getCanonicalPath();
    File dmd = new File(yooserBeen, "dmd");
    dmd.createNewFile();
    dmd.setExecutable(true);
    BuckConfig delegate = FakeBuckConfig.builder()
      .setEnvironment(ImmutableMap.of("PATH", userBean))
      .build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    String msg = "";
    Tool compiler = null;
    try {
      compiler = dBuckConfig.getDCompiler();
    } catch (HumanReadableException e) {
      msg = e.getMessage();
    }

    // OS X searches the paths in /etc/paths in addition to those specified in
    // the PATH environment variable. Since dmd may be on one of those paths,
    // we may find it there, resulting in no exception being thrown. When this
    // happens, assert that the compiler we found is at neither of the paths
    // we specify in the test.
    if (delegate.getPlatform() == Platform.MACOS && msg.length() == 0) {
      assertNotNull(compiler);
      assertFalse(toolPath(compiler).contains(userBean.toString()));
      assertFalse(toolPath(compiler).contains(yooserBeen.toString()));
    } else {
      assertEquals(
        "Unable to locate dmd on PATH, or it's not marked as being executable",
        msg);
    }
  }

  @Test
  public void testCompilerOverridden() throws IOException {
    File yooserBeen = tmp.newFolder("yooser", "been");
    File dmd = new File(yooserBeen, "dmd");
    dmd.createNewFile();
    dmd.setExecutable(true);
    File ldc = new File(yooserBeen, "ldc");
    ldc.createNewFile();
    ldc.setExecutable(true);
    BuckConfig delegate = FakeBuckConfig.builder()
      .setEnvironment(ImmutableMap.of(
              "PATH", yooserBeen.getCanonicalPath()))
      .setSections(
          "[d]",
          "compiler=" + ldc.getCanonicalPath())
      .build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    assertEquals(ldc.getCanonicalPath(), toolPath(dBuckConfig.getDCompiler()));
  }

  @Test
  public void testDCompilerFlagsOverridden() throws IOException {
    BuckConfig delegate = FakeBuckConfig.builder()
      .setSections(
          "[d]",
          "base_compiler_flags=-g -O3")
      .build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    ImmutableList<String> compilerFlags = dBuckConfig.getBaseCompilerFlags();
    assertContains(compilerFlags, "-g");
    assertContains(compilerFlags, "-O3");
  }

  @Test
  public void testDLinkerFlagsOverridden() throws IOException {
    File yooserBin = tmp.newFolder("yooser", "bin");
    File yooserLib = tmp.newFolder("yooser", "lib");
    File dmd = new File(yooserBin, "dmd");
    dmd.createNewFile();
    dmd.setExecutable(true);
    File phobos2So = new File(yooserLib, "libphobos2.so");
    phobos2So.createNewFile();
    BuckConfig delegate = FakeBuckConfig.builder()
      .setEnvironment(ImmutableMap.of(
              "PATH", yooserBin.getCanonicalPath()))
      .setSections(
          "[d]",
          "linker_flags = -L/opt/doesnotexist/dmd/lib \"-L/path with spaces\"")
      .build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    ImmutableList<String> linkerFlags = dBuckConfig.getLinkerFlags();
    assertContains(linkerFlags, "-L/opt/doesnotexist/dmd/lib");
    assertContains(linkerFlags, "-L/path with spaces");
    assertDoesNotContain(linkerFlags, "-L" + yooserLib.getCanonicalPath());
  }

  @Test
  public void testDRuntimeNearCompiler() throws IOException {
    File yooserBin = tmp.newFolder("yooser", "bin");
    File yooserLib = tmp.newFolder("yooser", "lib");
    File dmd = new File(yooserBin, "dmd");
    dmd.createNewFile();
    dmd.setExecutable(true);
    File phobos2So = new File(yooserLib, "libphobos2.so");
    phobos2So.createNewFile();
    BuckConfig delegate = FakeBuckConfig.builder()
      .setEnvironment(ImmutableMap.of(
              "PATH", yooserBin.getCanonicalPath()))
      .build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    ImmutableList<String> linkerFlags = dBuckConfig.getLinkerFlags();
    assertContains(linkerFlags, "-L" + yooserLib.getCanonicalPath());
  }

  private static <T> void assertContains(Collection<T> haystack, T needle) {
    assertTrue(
        needle.toString() + " is in " + haystack.toString(),
        haystack.contains(needle));
  }

  private static <T> void assertDoesNotContain(Collection<T> haystack, T needle) {
    assertFalse(
        needle.toString() + " is not in " + haystack.toString(),
        haystack.contains(needle));
  }

  /** Returns the path of a Tool. */
  private String toolPath(Tool tool) {
    BuildRuleResolver resolver = new BuildRuleResolver(
        TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    return tool.getCommandPrefix(new SourcePathResolver(resolver)).get(0);
  }
}
