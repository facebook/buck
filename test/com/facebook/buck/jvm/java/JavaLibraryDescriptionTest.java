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
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.Either;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.ConstructorArgMarshalException;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeExportDependenciesRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.NonCheckingBuildRuleFactoryParams;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
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
    defaults = JavacOptions.builder()
        .setSourceLevel("8")
        .setTargetLevel("8")
        .build();

    arg = new JavaLibraryDescription(defaults).createUnpopulatedConstructorArg();
    populateWithDefaultValues(arg);

    ruleResolver = new BuildRuleResolver();
    resolver = new SourcePathResolver(ruleResolver);
  }

  @Test
  public void javaVersionSetsBothSourceAndTargetLevels() {
    JavaLibraryDescription.Arg arg =
        new JavaLibraryDescription(defaults).createUnpopulatedConstructorArg();
    populateWithDefaultValues(arg);

    arg.source = Optional.absent();
    arg.target = Optional.absent();
    arg.javaVersion = Optional.of("1.4");  // Set in the past, so if we ever bump the default....

    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        resolver,
        arg,
        defaults).build();

    assertEquals("1.4", options.getSourceLevel());
    assertEquals("1.4", options.getTargetLevel());
  }

  @Test
  public void settingJavaVersionAndSourceLevelIsAnError() {
    JavaLibraryDescription.Arg arg =
        new JavaLibraryDescription(defaults).createUnpopulatedConstructorArg();
    populateWithDefaultValues(arg);

    arg.source = Optional.of("1.4");
    arg.target = Optional.absent();
    arg.javaVersion = Optional.of("1.4");

    try {
      JavaLibraryDescription.getJavacOptions(
          resolver,
          arg,
          defaults).build();
      fail();
    } catch (HumanReadableException e) {
      assertTrue(
          e.getMessage(),
          e.getHumanReadableErrorMessage().contains("either source and target or java_version"));
    }
  }

  @Test
  public void settingJavaVersionAndTargetLevelIsAnError() {
    JavaLibraryDescription.Arg arg =
        new JavaLibraryDescription(defaults).createUnpopulatedConstructorArg();
    populateWithDefaultValues(arg);

    arg.source = Optional.absent();
    arg.target = Optional.of("1.4");
    arg.javaVersion = Optional.of("1.4");

    try {
      JavaLibraryDescription.getJavacOptions(
          resolver,
          arg,
          defaults).build();
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
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        resolver,
        arg,
        defaults).build();

    Javac javac = options.getJavac();

    assertEquals(Optional.<SourcePath>absent(), options.getJavacJarPath());
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
    SourcePath sourcePath = new BuildTargetSourcePath(target);
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    arg.compiler = Optional.of(either);
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        resolver,
        arg,
        defaults).build();

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
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        resolver,
        arg,
        defaults).build();

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
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        resolver,
        arg,
        defaults).build();

    Javac javac = options.getJavac();

    assertEquals(Optional.<SourcePath>absent(), options.getJavacJarPath());
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
    SourcePath sourcePath = new BuildTargetSourcePath(target);
    Either<BuiltInJavac, SourcePath> either = Either.ofRight(sourcePath);

    arg.compiler = Optional.of(either);
    arg.javacJar = Optional.<SourcePath>of(
        new PathSourcePath(new FakeProjectFilesystem(), Paths.get("does-not-exist")));
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        resolver,
        arg,
        defaults).build();

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
    JavacOptions options = JavaLibraryDescription.getJavacOptions(
        resolver,
        arg,
        defaults).build();

    Javac javac = options.getJavac();

    assertEquals(Optional.of(new PathSourcePath(filesystem, expected)), options.getJavacJarPath());
    assertEquals(Optional.<Path>absent(), options.getJavacPath());
    assertTrue(javac.getClass().getName(), javac instanceof Jsr199Javac);
  }

  @Test
  public void rulesExportedFromDepsBecomeFirstOrderDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    FakeBuildRule exportedRule =
        resolver.addToIndex(new FakeBuildRule("//:exported_rule", pathResolver));
    FakeExportDependenciesRule exportingRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule("//:exporting_rule", pathResolver, exportedRule));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaLibrary = JavaLibraryBuilder.createBuilder(target)
        .addDep(exportingRule.getBuildTarget())
        .build(resolver);

    assertThat(javaLibrary.getDeps(), Matchers.<BuildRule>hasItem(exportedRule));
  }

  @Test
  public void rulesExportedFromProvidedDepsBecomeFirstOrderDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    FakeBuildRule exportedRule =
        resolver.addToIndex(new FakeBuildRule("//:exported_rule", pathResolver));
    FakeExportDependenciesRule exportingRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule("//:exporting_rule", pathResolver, exportedRule));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaLibrary = JavaLibraryBuilder.createBuilder(target)
        .addProvidedDep(exportingRule.getBuildTarget())
        .build(resolver);

    assertThat(javaLibrary.getDeps(), Matchers.<BuildRule>hasItem(exportedRule));
  }

  private void populateWithDefaultValues(Object arg) {
    BuildRuleFactoryParams factoryParams =
        NonCheckingBuildRuleFactoryParams.createNonCheckingBuildRuleFactoryParams(
            BuildTargetFactory.newInstance("//example:target"));

    try {
      new ConstructorArgMarshaller().populate(
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
