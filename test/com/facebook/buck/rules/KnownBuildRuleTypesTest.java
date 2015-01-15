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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.android.AndroidLibrary;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.java.DefaultJavaLibrary;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavacVersion;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.python.PythonEnvironment;
import com.facebook.buck.python.PythonVersion;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;


public class KnownBuildRuleTypesTest {

  @ClassRule public static TemporaryFolder folder = new TemporaryFolder();
  @Rule public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  private static final PythonEnvironment DUMMY_PYTHON_ENVIRONMENT =
      new PythonEnvironment(Paths.get("fake_python"), new PythonVersion("Python 2.7"));

  private static final String FAKE_XCODE_DEV_PATH = "/Fake/Path/To/Xcode.app/Contents/Developer";

  private static BuildRuleParams buildRuleParams;

  private static class TestDescription implements Description<Object> {

    public static final BuildRuleType TYPE = new BuildRuleType("known_rule_test");

    private final String value;

    private TestDescription(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    @Override
    public BuildRuleType getBuildRuleType() {
      return TYPE;
    }

    @Override
    public Object createUnpopulatedConstructorArg() {
      return new Object();
    }

    @Override
    public <A> BuildRule createBuildRule(
        BuildRuleParams params,
        BuildRuleResolver resolver,
        A args) {
      return null;
    }
  }

  @BeforeClass
  public static void setupBuildParams() throws IOException {
    buildRuleParams = new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:foo"))
        .build();
  }

  private void populateJavaArg(JavaLibraryDescription.Arg arg) {
    arg.srcs = Optional.of(ImmutableSortedSet.<SourcePath>of());
    arg.resources = Optional.of(ImmutableSortedSet.<SourcePath>of());
    arg.source = Optional.absent();
    arg.target = Optional.absent();
    arg.extraArguments = Optional.absent();
    arg.proguardConfig = Optional.absent();
    arg.annotationProcessorDeps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.annotationProcessorParams = Optional.of(ImmutableList.<String>of());
    arg.annotationProcessors = Optional.of(ImmutableSet.<String>of());
    arg.annotationProcessorOnly = Optional.absent();
    arg.postprocessClassesCommands = Optional.of(ImmutableList.<String>of());
    arg.resourcesRoot = Optional.absent();
    arg.providedDeps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.exportedDeps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
  }

  private DefaultJavaLibrary createJavaLibrary(KnownBuildRuleTypes buildRuleTypes) {
    JavaLibraryDescription description =
        (JavaLibraryDescription) buildRuleTypes.getDescription(JavaLibraryDescription.TYPE);

    JavaLibraryDescription.Arg arg = new JavaLibraryDescription.Arg();
    populateJavaArg(arg);
    return (DefaultJavaLibrary) description.createBuildRule(
        buildRuleParams,
        new BuildRuleResolver(),
        arg);
  }

  @Test
  public void whenJavacIsNotSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithAbsentJavac()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    FakeBuckConfig buckConfig = new FakeBuckConfig();

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        createExecutor(),
        new FakeAndroidDirectoryResolver(),
        DUMMY_PYTHON_ENVIRONMENT).build();
    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);
    assertEquals(Optional.<String> absent(), libraryRule.getJavac());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithJavacSet()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "");

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        processExecutor,
        new FakeAndroidDirectoryResolver(),
        DUMMY_PYTHON_ENVIRONMENT)
        .build();

    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);
    assertEquals(javac.toPath(), libraryRule.getJavac().get());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithJavacVersionSet()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);

    JavacVersion javacVersion = new JavacVersion("fakeVersion 0.1");

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "fakeVersion 0.1");

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        processExecutor,
        new FakeAndroidDirectoryResolver(),
        DUMMY_PYTHON_ENVIRONMENT)
        .build();
    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);
    assertEquals(javacVersion, libraryRule.getJavacVersion().get());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithDifferentRuleKey()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);

    KnownBuildRuleTypes buildRuleTypes =
        DefaultKnownBuildRuleTypes.getDefaultKnownBuildRuleTypes(new FakeProjectFilesystem());
    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "fakeVersion 0.1");
    KnownBuildRuleTypes configuredBuildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        processExecutor,
        new FakeAndroidDirectoryResolver(),
        DUMMY_PYTHON_ENVIRONMENT)
        .build();
    DefaultJavaLibrary configuredRule = createJavaLibrary(configuredBuildRuleTypes);

    assertNotEquals(libraryRule.getRuleKey(), configuredRule.getRuleKey());
  }

  @Test
  public void differentExternalJavacCreateJavaLibraryRulesWithDifferentRuleKey()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);
    ProcessExecutor processExecutor = createExecutor(javac.toString(), "fakeVersion 0.1");

    KnownBuildRuleTypes configuredBuildRuleTypes1 = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        processExecutor,
        new FakeAndroidDirectoryResolver(),
        DUMMY_PYTHON_ENVIRONMENT)
        .build();
    DefaultJavaLibrary configuredRule1 = createJavaLibrary(configuredBuildRuleTypes1);

    ProcessExecutor processExecutor2 = createExecutor(javac.toString(), "fakeVersion 0.2");
    KnownBuildRuleTypes configuredBuildRuleTypes2 = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        processExecutor2,
        new FakeAndroidDirectoryResolver(),
        DUMMY_PYTHON_ENVIRONMENT)
        .build();
    DefaultJavaLibrary configuredRule2 = createJavaLibrary(configuredBuildRuleTypes2);

    assertNotEquals(configuredRule1.getRuleKey(), configuredRule2.getRuleKey());
  }

  @Test
  public void whenJavacIsNotSetInBuckConfigConfiguredRulesCreateAndroidLibraryRuleWithAbsentJavac()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    FakeBuckConfig buckConfig = new FakeBuckConfig();

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        createExecutor(),
        new FakeAndroidDirectoryResolver(),
        new PythonEnvironment(Paths.get("fake_python"), new PythonVersion("Python 2.7"))).build();
    AndroidLibraryDescription description =
        (AndroidLibraryDescription) buildRuleTypes.getDescription(AndroidLibraryDescription.TYPE);

    AndroidLibraryDescription.Arg arg = new AndroidLibraryDescription.Arg();
    populateJavaArg(arg);
    arg.manifest = Optional.absent();
    AndroidLibrary rule = (AndroidLibrary) description.createBuildRule(
        buildRuleParams,
        new BuildRuleResolver(),
        arg);
    assertEquals(Optional.absent(), rule.getJavac());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateAndroidLibraryBuildRuleWithJavacSet()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "");

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        processExecutor,
    new FakeAndroidDirectoryResolver(),
        DUMMY_PYTHON_ENVIRONMENT)
        .build();
    AndroidLibraryDescription description =
        (AndroidLibraryDescription) buildRuleTypes.getDescription(AndroidLibraryDescription.TYPE);

    AndroidLibraryDescription.Arg arg = new AndroidLibraryDescription.Arg();
    populateJavaArg(arg);
    arg.manifest = Optional.absent();
    AndroidLibrary rule = (AndroidLibrary) description.createBuildRule(
        buildRuleParams,
        new BuildRuleResolver(),
        arg);
    assertEquals(javac.toPath(), rule.getJavac().get());
  }

  @Test
  public void whenRegisteringDescriptionsLastOneWins()
      throws IOException, NoSuchBuildTargetException {

    KnownBuildRuleTypes.Builder buildRuleTypesBuilder = KnownBuildRuleTypes.builder();
    buildRuleTypesBuilder.register(new TestDescription("Foo"));
    buildRuleTypesBuilder.register(new TestDescription("Bar"));
    buildRuleTypesBuilder.register(new TestDescription("Raz"));

    KnownBuildRuleTypes buildRuleTypes = buildRuleTypesBuilder.build();

    assertEquals(
        "Only one description should have wound up in the final KnownBuildRuleTypes",
        KnownBuildRuleTypes.builder().build().getAllDescriptions().size() + 1,
        buildRuleTypes.getAllDescriptions().size());

    boolean foundTestDescription = false;
    for (Description<?> description : buildRuleTypes.getAllDescriptions()) {
      if (description.getBuildRuleType().equals(TestDescription.TYPE)) {
        assertFalse("Should only find one test description", foundTestDescription);
        foundTestDescription = true;
        assertEquals(
            "Last description should have won",
            "Raz",
            ((TestDescription) description).getValue());
      }
    }
  }

  @Test
  public void createInstanceShouldReturnDifferentInstancesIfCalledWithDifferentParameters()
      throws IOException, InterruptedException {
    KnownBuildRuleTypes knownBuildRuleTypes1 = KnownBuildRuleTypes.createInstance(
        new FakeBuckConfig(),
        createExecutor(),
        new FakeAndroidDirectoryResolver(),
        DUMMY_PYTHON_ENVIRONMENT);

    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);
    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "");

    KnownBuildRuleTypes knownBuildRuleTypes2 = KnownBuildRuleTypes.createInstance(
        buckConfig,
        processExecutor,
        new FakeAndroidDirectoryResolver(),
        DUMMY_PYTHON_ENVIRONMENT);

    assertNotEquals(knownBuildRuleTypes1, knownBuildRuleTypes2);
  }

  private ProcessExecutor createExecutor() throws IOException {
    File javac = temporaryFolder.newFile();
    javac.setExecutable(true);
    return createExecutor(javac.toString(), "");
  }

  private ProcessExecutor createExecutor(String javac, String version) {
    Map<ProcessExecutorParams, FakeProcess> processMap = new HashMap<>();

      FakeProcess process = new FakeProcess(0, "", version);
      ProcessExecutorParams params = ProcessExecutorParams.builder()
          .setCommand(Lists.newArrayList(javac, "-version"))
          .build();
      processMap.put(params, process);

    addXcodeSelectProcess(processMap, FAKE_XCODE_DEV_PATH);

    return new FakeProcessExecutor(processMap);
  }

  private static void addXcodeSelectProcess(
      Map<ProcessExecutorParams, FakeProcess> processMap,
      String xcodeSelectPath) {

    FakeProcess xcodeSelectOutputProcess = new FakeProcess(0, xcodeSelectPath, "");
    ProcessExecutorParams xcodeSelectParams = ProcessExecutorParams.builder()
        .setCommand(Lists.newArrayList("xcode-select", "--print-path"))
        .build();
    processMap.put(xcodeSelectParams, xcodeSelectOutputProcess);
  }

}
