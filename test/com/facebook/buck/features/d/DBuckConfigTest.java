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

package com.facebook.buck.features.d;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests that the autodetection in DBuckConfig works and that explicitly configured values are
 * respected.
 */
public class DBuckConfigTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private Path makeFakeExecutable(Path directory, String baseName) throws IOException {
    Path dmd = directory.resolve(baseName + (Platform.detect() == Platform.WINDOWS ? ".exe" : ""));
    Files.createFile(dmd);
    MostFiles.makeExecutable(dmd);
    return dmd;
  }

  @Test
  public void testCompilerInPath() throws IOException {
    Path yooserBeen = tmp.newFolder("yooser", "been");
    Path dmd = makeFakeExecutable(yooserBeen, "dmd");
    BuckConfig delegate =
        FakeBuckConfig.builder()
            .setEnvironment(ImmutableMap.of("PATH", yooserBeen.toRealPath().toString()))
            .build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    assertEquals(dmd.toRealPath().toString(), toolPath(dBuckConfig.getDCompiler()));
  }

  @Test
  public void testCompilerNotInPath() throws IOException {
    Path yooserBeen = tmp.newFolder("yooser", "been");
    Path userBean = tmp.newFolder("user", "bean").toRealPath();
    makeFakeExecutable(yooserBeen, "dmd");
    BuckConfig delegate =
        FakeBuckConfig.builder()
            .setEnvironment(ImmutableMap.of("PATH", userBean.toString()))
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
      assertEquals("Unable to locate dmd on PATH, or it's not marked as being executable", msg);
    }
  }

  @Test
  public void testCompilerOverridden() throws IOException {
    Path yooserBeen = tmp.newFolder("yooser", "been");
    makeFakeExecutable(yooserBeen, "dmd");
    Path ldc = makeFakeExecutable(yooserBeen, "ldc");
    BuckConfig delegate =
        FakeBuckConfig.builder()
            .setEnvironment(ImmutableMap.of("PATH", yooserBeen.toRealPath().toString()))
            .setSections("[d]", "compiler=" + ldc.toRealPath())
            .build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    assertEquals(ldc.toRealPath().toString(), toolPath(dBuckConfig.getDCompiler()));
  }

  @Test
  public void testDCompilerFlagsOverridden() {
    BuckConfig delegate =
        FakeBuckConfig.builder().setSections("[d]", "base_compiler_flags=-g -O3").build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    ImmutableList<String> compilerFlags = dBuckConfig.getBaseCompilerFlags();
    assertContains(compilerFlags, "-g");
    assertContains(compilerFlags, "-O3");
  }

  @Test
  public void testDLinkerFlagsOverridden() throws IOException {
    Path yooserBin = tmp.newFolder("yooser", "bin");
    Path yooserLib = tmp.newFolder("yooser", "lib");
    makeFakeExecutable(yooserBin, "dmd");
    Path phobos2So = yooserLib.resolve("libphobos2.so");
    Files.createFile(phobos2So);
    BuckConfig delegate =
        FakeBuckConfig.builder()
            .setEnvironment(ImmutableMap.of("PATH", yooserBin.toRealPath().toString()))
            .setSections(
                "[d]", "linker_flags = -L/opt/doesnotexist/dmd/lib \"-L/path with spaces\"")
            .build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    ImmutableList<String> linkerFlags = dBuckConfig.getLinkerFlags();
    assertContains(linkerFlags, "-L/opt/doesnotexist/dmd/lib");
    assertContains(linkerFlags, "-L/path with spaces");
    assertDoesNotContain(linkerFlags, "-L" + yooserLib.toRealPath());
  }

  @Test
  public void testDRuntimeNearCompiler() throws IOException {
    Path yooserBin = tmp.newFolder("yooser", "bin");
    Path yooserLib = tmp.newFolder("yooser", "lib");
    makeFakeExecutable(yooserBin, "dmd");
    Path phobos2So = yooserLib.resolve("libphobos2.so");
    Files.createFile(phobos2So);
    BuckConfig delegate =
        FakeBuckConfig.builder()
            .setEnvironment(ImmutableMap.of("PATH", yooserBin.toRealPath().toString()))
            .build();
    DBuckConfig dBuckConfig = new DBuckConfig(delegate);
    ImmutableList<String> linkerFlags = dBuckConfig.getLinkerFlags();
    assertContains(linkerFlags, "-L" + yooserLib.toRealPath());
  }

  private static <T> void assertContains(Collection<T> haystack, T needle) {
    assertTrue(needle + " is in " + haystack, haystack.contains(needle));
  }

  private static <T> void assertDoesNotContain(Collection<T> haystack, T needle) {
    assertFalse(needle + " is not in " + haystack, haystack.contains(needle));
  }

  /** Returns the path of a Tool. */
  private String toolPath(Tool tool) {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    return tool.getCommandPrefix(DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver)))
        .get(0);
  }
}
