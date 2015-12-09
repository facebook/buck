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
import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.Either;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.ConstructorArgMarshalException;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.NonCheckingBuildRuleFactoryParams;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class JavacArgInterpreterTest {
  private JavacOptions defaults;
  private JavacArg arg;
  private BuildRuleResolver ruleResolver;
  private SourcePathResolver resolver;

  @Before
  public void createHelpers() {
    defaults = JavacOptions.builder()
        .setSourceLevel("8")
        .setTargetLevel("8")
        .build();

    arg = new JavacArg();
    populateWithDefaultValues(arg);

    ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    resolver = new SourcePathResolver(ruleResolver);
  }

  @Test
  public void javaVersionSetsBothSourceAndTargetLevels() {
    arg.source = Optional.absent();
    arg.target = Optional.absent();
    arg.javaVersion = Optional.of("1.4");  // Set in the past, so if we ever bump the default....

    JavacOptions options = createJavacOptions(arg);

    assertEquals("1.4", options.getSourceLevel());
    assertEquals("1.4", options.getTargetLevel());
  }

  @Test
  public void settingJavaVersionAndSourceLevelIsAnError() {
    arg.source = Optional.of("1.4");
    arg.target = Optional.absent();
    arg.javaVersion = Optional.of("1.4");

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
    arg.source = Optional.absent();
    arg.target = Optional.of("1.4");
    arg.javaVersion = Optional.of("1.4");

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
  public void compilerArgWithDefaultValueReturnsJsr199Javac() {
    Either<BuiltInJavac, SourcePath> either = Either.ofLeft(DEFAULT);
    arg.compiler = Optional.of(either);
    JavacOptions options = createJavacOptions(arg);

    Javac javac = options.getJavac();

    assertEquals(Optional.<SourcePath>absent(), options.getJavacJarPath());
    assertEquals(Optional.<Path>absent(), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof Jsr199Javac);
  }

  @Test
  public void compilerArgWithPrebuiltJarValueReturnsJsr199Javac() throws Exception {
    Path javacJarPath = Paths.get("langtools").resolve("javac.jar");
    BuildTarget target = BuildTargetFactory.newInstance("//langtools:javac");
    PrebuiltJarBuilder.createBuilder(target)
        .setBinaryJar(javacJarPath)
        .build(ruleResolver);
    SourcePath sourcePath = new BuildTargetSourcePath(target);
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    arg.compiler = Optional.of(either);
    JavacOptions options = createJavacOptions(arg);

    Javac javac = options.getJavac();

    assertEquals(Optional.of(sourcePath), options.getJavacJarPath());
    assertEquals(Optional.<Path>absent(), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof Jsr199Javac);
  }

  @Test
  public void compilerArgWithPathReturnsExternalJavac() {
    Path externalJavac = Paths.get("/foo/bar/javac.exe");
    Either<BuiltInJavac, SourcePath> either =
        Either.ofRight((SourcePath) new FakeSourcePath(externalJavac.toString()));

    arg.compiler = Optional.of(either);
    JavacOptions options = createJavacOptions(arg);

    Javac javac = options.getJavac();

    assertEquals(Optional.<SourcePath>absent(), options.getJavacJarPath());
    assertEquals(Optional.of(externalJavac), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof ExternalJavac);
  }

  @Test
  public void compilerArgTakesPrecedenceOverJavacPathArg() {
    Path externalJavac = Paths.get("/foo/bar/javac.exe");
    SourcePath sourcePath = new FakeSourcePath(externalJavac.toString());
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    arg.compiler = Optional.of(either);
    arg.javac = Optional.of(Paths.get("does-not-exist"));
    JavacOptions options = createJavacOptions(arg);

    Javac javac = options.getJavac();

    assertEquals(Optional.<SourcePath>absent(), options.getJavacJarPath());
    assertEquals(Optional.of(externalJavac), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof ExternalJavac);
  }

  @Test
  public void compilerArgTakesPrecedenceOverJavacJarArg() throws Exception {
    Path javacJarPath = Paths.get("langtools").resolve("javac.jar");
    BuildTarget target = BuildTargetFactory.newInstance("//langtools:javac");
    PrebuiltJarBuilder.createBuilder(target)
        .setBinaryJar(javacJarPath)
        .build(ruleResolver);
    SourcePath sourcePath = new BuildTargetSourcePath(target);
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    arg.compiler = Optional.of(either);
    arg.javacJar = Optional.<SourcePath>of(
        new PathSourcePath(new FakeProjectFilesystem(), Paths.get("does-not-exist")));
    JavacOptions options = createJavacOptions(arg);

    Javac javac = options.getJavac();

    assertEquals(Optional.of(sourcePath), options.getJavacJarPath());
    assertEquals(Optional.<Path>absent(), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof Jsr199Javac);
  }

  @Test
  public void omittingTheCompilerArgMeansThatExistingBehaviourIsMaintained() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path expected = Paths.get("does-not-exist");

    arg.compiler = Optional.absent();
    arg.javacJar = Optional.<SourcePath>of(
        new PathSourcePath(new FakeProjectFilesystem(), expected));
    JavacOptions options = createJavacOptions(arg);

    Javac javac = options.getJavac();

    assertEquals(Optional.of(new PathSourcePath(filesystem, expected)), options.getJavacJarPath());
    assertEquals(Optional.<Path>absent(), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof Jsr199Javac);
  }

  private JavacOptions createJavacOptions(JavacArg arg) {
    return JavacArgInterpreter.populateJavacOptions(
        defaults,
        new FakeBuildRuleParamsBuilder("//not:real").build(),
        ruleResolver,
        resolver,
        arg);
  }

  private void populateWithDefaultValues(Object arg) {
    BuildRuleFactoryParams factoryParams =
        NonCheckingBuildRuleFactoryParams.createNonCheckingBuildRuleFactoryParams(
            BuildTargetFactory.newInstance("//example:target"));

    try {
      new ConstructorArgMarshaller(new DefaultTypeCoercerFactory()).populate(
          createCellRoots(factoryParams.getProjectFilesystem()),
          factoryParams.getProjectFilesystem(),
          factoryParams,
          arg,
          ImmutableSet.<BuildTarget>builder(),
          ImmutableSet.<BuildTargetPattern>builder(),
          ImmutableMap.<String, Object>of());
    } catch (ConstructorArgMarshalException | NoSuchBuildTargetException error) {
      throw Throwables.propagate(error);
    }
  }
}
