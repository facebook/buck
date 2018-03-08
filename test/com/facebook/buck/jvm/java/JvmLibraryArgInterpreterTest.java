/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.java.BuiltInJavac.DEFAULT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.types.Either;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;
import org.junit.Before;
import org.junit.Test;

public class JvmLibraryArgInterpreterTest {
  private JavacOptions defaults;
  private BuildRuleResolver ruleResolver;

  @Before
  public void createHelpers() {
    defaults = JavacOptions.builder().setSourceLevel("8").setTargetLevel("8").build();

    ruleResolver = new TestBuildRuleResolver();
  }

  @Test
  public void javaVersionSetsBothSourceAndTargetLevels() {
    // Set in the past, so if we ever bump the default....
    JvmLibraryArg arg = ExampleJvmLibraryArg.builder().setName("foo").setJavaVersion("1.4").build();

    JavacOptions options = createJavacOptions(arg);

    assertEquals("1.4", options.getSourceLevel());
    assertEquals("1.4", options.getTargetLevel());
  }

  @Test
  public void settingJavaVersionAndSourceLevelIsAnError() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setSource("1.4")
            .setJavaVersion("1.4")
            .build();

    try {
      createJavacOptions(arg);
      fail();
    } catch (HumanReadableException e) {
      assertTrue(
          e.getMessage(),
          e.getHumanReadableErrorMessage().contains("either source and target or java_version"));
    }
  }

  @Test
  public void settingJavaVersionAndTargetLevelIsAnError() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setTarget("1.4")
            .setJavaVersion("1.4")
            .build();

    try {
      createJavacOptions(arg);
      fail();
    } catch (HumanReadableException e) {
      assertTrue(
          e.getMessage(),
          e.getHumanReadableErrorMessage().contains("either source and target or java_version"));
    }
  }

  @Test
  public void compilerArgIsSet() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder().setName("foo").setCompiler(Either.ofLeft(DEFAULT)).build();

    assertEquals(arg.getCompiler(), arg.getJavacSpec().getCompiler());
  }

  @Test
  public void javacArgIsSet() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder().setName("foo").setJavac(Paths.get("does-not-exist")).build();

    assertEquals(
        Optional.of(Either.ofLeft(arg.getJavac().get())), arg.getJavacSpec().getJavacPath());
  }

  @Test
  public void testJavacJarArgIsSet() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setJavacJar(FakeSourcePath.of("does-not-exist"))
            .build();

    assertEquals(arg.getJavacJar(), arg.getJavacSpec().getJavacJarPath());
  }

  @Test
  public void testCompilerClassNameArgIsSet() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setJavacJar(FakeSourcePath.of("does-not-exist"))
            .setCompilerClassName("compiler")
            .build();

    assertEquals(arg.getCompilerClassName(), arg.getJavacSpec().getCompilerClassName());
  }

  @Test
  public void testNoJavacSpecIfNoJavacArg() {
    JvmLibraryArg arg = ExampleJvmLibraryArg.builder().setName("foo").build();
    assertNull(arg.getJavacSpec());
  }

  private JavacOptions createJavacOptions(JvmLibraryArg arg) {
    return JavacOptionsFactory.create(
        defaults,
        BuildTargetFactory.newInstance("//not:real"),
        new FakeProjectFilesystem(),
        ruleResolver,
        arg);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractExampleJvmLibraryArg extends JvmLibraryArg {}
}
