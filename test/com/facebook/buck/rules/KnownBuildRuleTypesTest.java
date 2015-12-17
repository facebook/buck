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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JvmLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hashing;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class KnownBuildRuleTypesTest {

  @ClassRule public static TemporaryFolder folder = new TemporaryFolder();
  @Rule public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  private static final String FAKE_XCODE_DEV_PATH = "/Fake/Path/To/Xcode.app/Contents/Developer";
  private static final ImmutableMap<String, String> environment =
      ImmutableMap.copyOf(System.getenv());

  private static BuildRuleParams buildRuleParams;

  private static class TestDescription implements Description<Object> {

    public static final BuildRuleType TYPE = BuildRuleType.of("known_rule_test");

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
        TargetGraph targetGraph,
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
    arg.javaVersion = Optional.absent();
    arg.javac = Optional.absent();
    arg.javacJar = Optional.absent();
    arg.compiler = Optional.absent();
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
    arg.tests = Optional.of(ImmutableSortedSet.<BuildTarget>of());
  }

  private DefaultJavaLibrary createJavaLibrary(KnownBuildRuleTypes buildRuleTypes) {
    JvmLibraryDescription description =
        (JvmLibraryDescription) buildRuleTypes.getDescription(JavaLibraryDescription.TYPE);

    JavaLibraryDescription.Arg arg = new JavaLibraryDescription.Arg();
    populateJavaArg(arg);
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    return (DefaultJavaLibrary) description
        .createBuildRule(TargetGraph.EMPTY, buildRuleParams, resolver, arg);
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithDifferentRuleKey()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    final File javac = temporaryFolder.newFile();
    assertTrue(javac.setExecutable(true));

    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "tools", ImmutableMap.of("javac", javac.toString()));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();

    KnownBuildRuleTypes buildRuleTypes =
        DefaultKnownBuildRuleTypes.getDefaultKnownBuildRuleTypes(
            new FakeProjectFilesystem(), environment);
    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "fakeVersion 0.1");
    KnownBuildRuleTypes configuredBuildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        processExecutor,
        new FakeAndroidDirectoryResolver(),
        Optional.<Path>absent())
        .build();
    DefaultJavaLibrary configuredRule = createJavaLibrary(configuredBuildRuleTypes);

    SourcePathResolver resolver =
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
    Path javacPath = javac.toPath();
    FakeFileHashCache hashCache = new FakeFileHashCache(
        ImmutableMap.of(javacPath, MorePaths.asByteSource(javacPath).hash(Hashing.sha1())));
    RuleKey configuredKey = new DefaultRuleKeyBuilderFactory(hashCache, resolver).build(
        configuredRule);
    RuleKey libraryKey = new DefaultRuleKeyBuilderFactory(hashCache, resolver).build(
        libraryRule);

    assertNotEquals(libraryKey, configuredKey);
  }

  @Test
  public void whenRegisteringDescriptionsLastOneWins()
      throws IOException, NoSuchBuildTargetException {
    FlavorDomain<CxxPlatform> cxxPlatforms = new FlavorDomain<>(
        "C/C++ platform",
        ImmutableMap.<Flavor, CxxPlatform>of());
    CxxPlatform defaultPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;

    KnownBuildRuleTypes.Builder buildRuleTypesBuilder = KnownBuildRuleTypes.builder();
    buildRuleTypesBuilder.register(new TestDescription("Foo"));
    buildRuleTypesBuilder.register(new TestDescription("Bar"));
    buildRuleTypesBuilder.register(new TestDescription("Raz"));

    buildRuleTypesBuilder.setCxxPlatforms(
        cxxPlatforms);
    buildRuleTypesBuilder.setDefaultCxxPlatform(defaultPlatform);

    KnownBuildRuleTypes buildRuleTypes = buildRuleTypesBuilder.build();

    assertEquals(
        "Only one description should have wound up in the final KnownBuildRuleTypes",
        KnownBuildRuleTypes.builder()
            .setCxxPlatforms(cxxPlatforms)
            .setDefaultCxxPlatform(defaultPlatform)
            .build()
            .getAllDescriptions()
            .size() +
            1,
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
        FakeBuckConfig.builder().build(),
        createExecutor(),
        new FakeAndroidDirectoryResolver(),
        Optional.<Path>absent());

    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "tools", ImmutableMap.of("javac", javac.toString()));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "");

    KnownBuildRuleTypes knownBuildRuleTypes2 = KnownBuildRuleTypes.createInstance(
        buckConfig,
        processExecutor,
        new FakeAndroidDirectoryResolver(),
        Optional.<Path>absent());

    assertNotEquals(knownBuildRuleTypes1, knownBuildRuleTypes2);
  }

  @Test
  public void canSetDefaultPlatformToDefault() throws IOException,
        InterruptedException {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of("default_platform", "default"));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();

    // This would throw if "default" weren't available as a platform.
    KnownBuildRuleTypes.createBuilder(
        buckConfig,
        createExecutor(),
        new FakeAndroidDirectoryResolver(),
        Optional.<Path>absent()).build();
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
        .setCommand(ImmutableList.of(javac, "-version"))
        .build();
    processMap.put(params, process);

    addXcodeSelectProcess(processMap, FAKE_XCODE_DEV_PATH);

    processMap.putAll(
        DefaultKnownBuildRuleTypes.getPythonProcessMap(
            DefaultKnownBuildRuleTypes.getPaths(environment)));

    return new FakeProcessExecutor(processMap);
  }

  private static void addXcodeSelectProcess(
      Map<ProcessExecutorParams, FakeProcess> processMap,
      String xcodeSelectPath) {

    FakeProcess xcodeSelectOutputProcess = new FakeProcess(0, xcodeSelectPath, "");
    ProcessExecutorParams xcodeSelectParams = ProcessExecutorParams.builder()
        .setCommand(ImmutableList.of("xcode-select", "--print-path"))
        .build();
    processMap.put(xcodeSelectParams, xcodeSelectOutputProcess);
  }

}
