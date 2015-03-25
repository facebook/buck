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

package com.facebook.buck.java;

import static com.facebook.buck.java.BuiltInJavac.DEFAULT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.ConstructorArgMarshalException;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.NonCheckingBuildRuleFactoryParams;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ImmutableProcessExecutorParams;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class JavaLibraryDescriptionTest {

  private JavacOptions defaults;
  private JavaLibraryDescription.Arg arg;
  private BuildRuleResolver ruleResolver;
  private SourcePathResolver resolver;

  @Before
  public void createHelpers() {
    defaults = ImmutableJavacOptions.builder()
        .setSourceLevel("8")
        .setTargetLevel("8")
        .build();

    arg = new JavaLibraryDescription(defaults).createUnpopulatedConstructorArg();
    populateWithDefaultValues(arg);

    ruleResolver = new BuildRuleResolver();

    resolver = new SourcePathResolver(ruleResolver);
  }

  @Test
  public void compilerArgWithDefaultValueReturnsJsr199Javac() {
    Either<BuiltInJavac, Either<BuildTarget, Path>> either = Either.ofLeft(DEFAULT);
    arg.compiler = Optional.of(either);
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        ruleResolver,
        resolver,
        arg,
        defaults).build();

    Javac javac = options.getJavac();

    assertEquals(Optional.<Path>absent(), options.getJavacJarPath());
    assertEquals(Optional.<Path>absent(), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof Jsr199Javac);
  }

  @Test
  public void compilerArgWithPrebuiltJarValueReturnsJsr199Javac() {
    Path javacJarPath = Paths.get("langtools").resolve("javac.jar");
    BuildTarget target = BuildTargetFactory.newInstance("//langtools:javac");
    PrebuiltJarBuilder.createBuilder(target)
        .setBinaryJar(javacJarPath)
        .build(ruleResolver);
    Either<BuiltInJavac, Either<BuildTarget, Path>> either =
        Either.ofRight(Either.<BuildTarget, Path>ofLeft(target));

    arg.compiler = Optional.of(either);
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        ruleResolver,
        resolver,
        arg,
        defaults).build();

    Javac javac = options.getJavac();

    assertEquals(Optional.of(javacJarPath), options.getJavacJarPath());
    assertEquals(Optional.<Path>absent(), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof Jsr199Javac);
  }

  @Test
  public void compilerArgWithPathReturnsExternalJavac() {
    Path externalJavac = Paths.get("/foo/bar/javac.exe");
    Either<BuiltInJavac, Either<BuildTarget, Path>> either =
        Either.ofRight(Either.<BuildTarget, Path>ofRight(externalJavac));

    ProcessExecutorParams version = ImmutableProcessExecutorParams.builder()
        .setCommand(ImmutableList.of(externalJavac.toString(), "-version"))
        .build();
    FakeProcess process = new FakeProcess(0, "", "1.2.3");
    FakeProcessExecutor executor = new FakeProcessExecutor(ImmutableMap.of(version, process));
    ImmutableJavacOptions newDefaults = ImmutableJavacOptions.builder(defaults)
        .setProcessExecutor(executor)
        .build();

    arg.compiler = Optional.of(either);
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        ruleResolver,
        resolver,
        arg,
        newDefaults).build();

    Javac javac = options.getJavac();

    assertEquals(Optional.<Path>absent(), options.getJavacJarPath());
    assertEquals(Optional.of(externalJavac), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof ExternalJavac);
  }

  @Test
  public void compilerArgTakesPrecedenceOverJavacPathArg() {
    Path externalJavac = Paths.get("/foo/bar/javac.exe");
    Either<BuiltInJavac, Either<BuildTarget, Path>> either =
        Either.ofRight(Either.<BuildTarget, Path>ofRight(externalJavac));

    ProcessExecutorParams version = ImmutableProcessExecutorParams.builder()
        .setCommand(ImmutableList.of(externalJavac.toString(), "-version"))
        .build();
    FakeProcess process = new FakeProcess(0, "", "1.2.3");
    FakeProcessExecutor executor = new FakeProcessExecutor(ImmutableMap.of(version, process));
    ImmutableJavacOptions newDefaults = ImmutableJavacOptions.builder(defaults)
        .setProcessExecutor(executor)
        .build();

    arg.compiler = Optional.of(either);
    arg.javac = Optional.<SourcePath>of(
        new PathSourcePath(new FakeProjectFilesystem(), Paths.get("does-not-exist")));
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        ruleResolver,
        resolver,
        arg,
        newDefaults).build();

    Javac javac = options.getJavac();

    assertEquals(Optional.<Path>absent(), options.getJavacJarPath());
    assertEquals(Optional.of(externalJavac), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof ExternalJavac);
  }

  @Test
  public void compilerArgTakesPrecedenceOverJavacJarArg() {
    Path javacJarPath = Paths.get("langtools").resolve("javac.jar");
    BuildTarget target = BuildTargetFactory.newInstance("//langtools:javac");
    PrebuiltJarBuilder.createBuilder(target)
        .setBinaryJar(javacJarPath)
        .build(ruleResolver);
    Either<BuiltInJavac, Either<BuildTarget, Path>> either =
        Either.ofRight(Either.<BuildTarget, Path>ofLeft(target));

    arg.compiler = Optional.of(either);
    arg.javacJar = Optional.<SourcePath>of(
        new PathSourcePath(new FakeProjectFilesystem(), Paths.get("does-not-exist")));
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        ruleResolver,
        resolver,
        arg,
        defaults).build();

    Javac javac = options.getJavac();

    assertEquals(Optional.of(javacJarPath), options.getJavacJarPath());
    assertEquals(Optional.<Path>absent(), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof Jsr199Javac);
  }

  @Test
  public void omittingTheCompilerArgMeansThatExistingBehaviourIsMaintained() {
    Path expected = Paths.get("does-not-exist");

    arg.compiler = Optional.absent();
    arg.javacJar = Optional.<SourcePath>of(
        new PathSourcePath(new FakeProjectFilesystem(), expected));
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        ruleResolver,
        resolver,
        arg,
        defaults).build();

    Javac javac = options.getJavac();

    assertEquals(Optional.of(expected), options.getJavacJarPath());
    assertEquals(Optional.<Path>absent(), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof Jsr199Javac);
  }

  private void populateWithDefaultValues(Object arg) {
    BuildRuleFactoryParams factoryParams =
        NonCheckingBuildRuleFactoryParams.createNonCheckingBuildRuleFactoryParams(
            new BuildTargetParser(),
            BuildTargetFactory.newInstance("//example:target"));

    try {
      new ConstructorArgMarshaller().populate(
          new FakeProjectFilesystem(),
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
