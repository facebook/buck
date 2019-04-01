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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.immutables.value.Value;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JvmLibraryArgInterpreterTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private JavacOptions defaults;
  private ActionGraphBuilder graphBuilder;
  private SourcePathRuleFinder ruleFinder;
  private SourcePathResolver sourcePathResolver;

  @Before
  public void createHelpers() {
    defaults =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder().setSourceLevel("8").setTargetLevel("8").build())
            .build();
    graphBuilder = new TestActionGraphBuilder();
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
  }

  @Test
  public void javaVersionSetsBothSourceAndTargetLevels() {
    // Set in the past, so if we ever bump the default....
    JvmLibraryArg arg = ExampleJvmLibraryArg.builder().setName("foo").setJavaVersion("1.4").build();

    JavacOptions options = createJavacOptions(arg);

    assertEquals("1.4", options.getLanguageLevelOptions().getSourceLevel());
    assertEquals("1.4", options.getLanguageLevelOptions().getTargetLevel());
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
  public void javacArgIsSet() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setJavac(FakeSourcePath.of("does-not-exist"))
            .build();

    assertEquals(Optional.of(arg.getJavac().get()), arg.getJavacSpec(ruleFinder).getJavacPath());
  }

  @Test
  public void testJavacJarArgIsSet() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setJavacJar(FakeSourcePath.of("does-not-exist"))
            .build();

    assertEquals(arg.getJavacJar(), arg.getJavacSpec(ruleFinder).getJavacJarPath());
  }

  @Test
  public void testCompilerClassNameArgIsSet() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setJavacJar(FakeSourcePath.of("does-not-exist"))
            .setCompilerClassName("compiler")
            .build();

    assertEquals(arg.getCompilerClassName(), arg.getJavacSpec(ruleFinder).getCompilerClassName());
  }

  @Test
  public void returnsBuiltInJavacWhenCompilerArgHasDefault() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder().setName("foo").setCompiler(Either.ofLeft(DEFAULT)).build();

    Javac javac = arg.getJavacSpec(ruleFinder).getJavacProvider().resolve(ruleFinder);
    assertTrue(javac instanceof JdkProvidedInMemoryJavac);
  }

  @Test
  public void returnsExternalCompilerIfCompilerArgHasPath() throws IOException {
    // newExecutableFile cannot be executed on windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    Path externalJavac = tmp.newExecutableFile();
    SourcePath sourcePath = FakeSourcePath.of(externalJavac.toString());
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    JvmLibraryArg arg = ExampleJvmLibraryArg.builder().setName("foo").setCompiler(either).build();

    ExternalJavac javac =
        (ExternalJavac) arg.getJavacSpec(ruleFinder).getJavacProvider().resolve(ruleFinder);

    assertEquals(
        ImmutableList.of(externalJavac.toString()), javac.getCommandPrefix(sourcePathResolver));
  }

  @Test
  public void compilerArgTakesPrecedenceOverJavacPathArg() throws IOException {
    // newExecutableFile cannot be executed on windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    Path externalJavacPath = tmp.newExecutableFile();
    SourcePath sourcePath = FakeSourcePath.of(externalJavacPath.toString());
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setCompiler(either)
            .setJavac(FakeSourcePath.of("does-not-exist"))
            .build();

    ExternalJavac javac =
        (ExternalJavac) arg.getJavacSpec(ruleFinder).getJavacProvider().resolve(ruleFinder);

    assertEquals(
        ImmutableList.of(externalJavacPath.toString()), javac.getCommandPrefix(sourcePathResolver));
  }

  @Test
  public void returnsJarBackedJavacWhenCompilerArgIsPrebuiltJar() {
    Path javacJarPath = Paths.get("langtools").resolve("javac.jar");
    BuildTarget target = BuildTargetFactory.newInstance("//langtools:javac");
    PrebuiltJarBuilder.createBuilder(target).setBinaryJar(javacJarPath).build(graphBuilder);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(target);
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    JvmLibraryArg arg = ExampleJvmLibraryArg.builder().setName("foo").setCompiler(either).build();

    JarBackedJavac javac =
        (JarBackedJavac) arg.getJavacSpec(ruleFinder).getJavacProvider().resolve(ruleFinder);

    ImmutableList<SourcePath> inputs =
        BuildableSupport.deriveInputs(javac).collect(ImmutableList.toImmutableList());
    assertThat(inputs, Matchers.hasItem(DefaultBuildTargetSourcePath.of(target)));
  }

  @Test
  public void compilerArgTakesPrecedenceOverJavacJarArg() {
    Path javacJarPath = Paths.get("langtools").resolve("javac.jar");
    BuildTarget target = BuildTargetFactory.newInstance("//langtools:javac");
    PrebuiltJar prebuiltJar =
        PrebuiltJarBuilder.createBuilder(target).setBinaryJar(javacJarPath).build(graphBuilder);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(target);
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setCompiler(either)
            .setJavacJar(
                PathSourcePath.of(new FakeProjectFilesystem(), Paths.get("does-not-exist")))
            .build();

    JarBackedJavac javac =
        (JarBackedJavac) arg.getJavacSpec(ruleFinder).getJavacProvider().resolve(ruleFinder);

    assertThat(
        BuildableSupport.deriveInputs(javac).collect(ImmutableList.toImmutableList()),
        Matchers.hasItem(prebuiltJar.getSourcePathToOutput()));
  }

  @Test
  public void testNoJavacSpecIfNoJavacArg() {
    JvmLibraryArg arg = ExampleJvmLibraryArg.builder().setName("foo").build();
    assertNull(arg.getJavacSpec(ruleFinder));
  }

  private JavacOptions createJavacOptions(JvmLibraryArg arg) {
    return JavacOptionsFactory.create(
        defaults, BuildTargetFactory.newInstance("//not:real"), graphBuilder, arg);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractExampleJvmLibraryArg extends JvmLibraryArg {}
}
