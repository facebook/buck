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

import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class KnownBuildRuleTypesTest {

  @ClassRule public static TemporaryFolder folder = new TemporaryFolder();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private static final String FAKE_XCODE_DEV_PATH = "/Fake/Path/To/Xcode.app/Contents/Developer";
  private static final ImmutableMap<String, String> environment =
      ImmutableMap.copyOf(System.getenv());

  private static BuildRuleParams buildRuleParams;

  private static class TestDescription implements Description<TestDescription.Arg> {

    static class Arg extends AbstractDescriptionArg {
    };

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
    public Arg createUnpopulatedConstructorArg() {
      return new Arg();
    }

    @Override
    public <A extends Arg> BuildRule createBuildRule(
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
    arg.removeClasses = Optional.absent();
    arg.proguardConfig = Optional.absent();
    arg.annotationProcessorDeps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.annotationProcessorParams = Optional.of(ImmutableList.<String>of());
    arg.annotationProcessors = Optional.of(ImmutableSet.<String>of());
    arg.annotationProcessorOnly = Optional.absent();
    arg.postprocessClassesCommands = Optional.of(ImmutableList.<String>of());
    arg.resourcesRoot = Optional.absent();
    arg.manifestFile = Optional.absent();
    arg.providedDeps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.exportedDeps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.tests = Optional.of(ImmutableSortedSet.<BuildTarget>of());
  }

  private DefaultJavaLibrary createJavaLibrary(KnownBuildRuleTypes buildRuleTypes) {
    JavaLibraryDescription description =
        (JavaLibraryDescription) buildRuleTypes.getDescription(JavaLibraryDescription.TYPE);

    JavaLibraryDescription.Arg arg = new JavaLibraryDescription.Arg();
    populateJavaArg(arg);
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    return (DefaultJavaLibrary) description
        .createBuildRule(TargetGraph.EMPTY, buildRuleParams, resolver, arg);
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithDifferentRuleKey()
      throws Exception {
    final Path javac;
    if (Platform.detect() == Platform.WINDOWS) {
      javac = Paths.get("C:/Windows/system32/rundll32.exe");
    } else {
      javac = temporaryFolder.newExecutableFile();
    }

    ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRoot());
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "tools", ImmutableMap.of("javac", javac.toString()));
    BuckConfig buckConfig = FakeBuckConfig
        .builder()
        .setFilesystem(filesystem)
        .setSections(sections)
        .build();

    KnownBuildRuleTypes buildRuleTypes =
        DefaultKnownBuildRuleTypes.getDefaultKnownBuildRuleTypes(
            filesystem, environment);
    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "fakeVersion 0.1");
    KnownBuildRuleTypes configuredBuildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        filesystem, processExecutor,
        new FakeAndroidDirectoryResolver())
        .build();
    DefaultJavaLibrary configuredRule = createJavaLibrary(configuredBuildRuleTypes);

    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     );
    FakeFileHashCache hashCache = new FakeFileHashCache(
        ImmutableMap.of(javac, MorePaths.asByteSource(javac).hash(Hashing.sha1())));
    RuleKey configuredKey = new DefaultRuleKeyBuilderFactory(0, hashCache, resolver).build(
        configuredRule);
    RuleKey libraryKey = new DefaultRuleKeyBuilderFactory(0, hashCache, resolver).build(
        libraryRule);

    assertNotEquals(libraryKey, configuredKey);
  }

  @Test
  public void whenRegisteringDescriptionsLastOneWins()
      throws Exception {
    FlavorDomain<CxxPlatform> cxxPlatforms = FlavorDomain.of("C/C++ platform");
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
      throws Exception {
    ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRoot());
    KnownBuildRuleTypes knownBuildRuleTypes1 = KnownBuildRuleTypes.createInstance(
        FakeBuckConfig.builder().build(),
        filesystem,
        createExecutor(),
        new FakeAndroidDirectoryResolver());

    final Path javac = temporaryFolder.newExecutableFile();
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "tools", ImmutableMap.of("javac", javac.toString()));
    BuckConfig buckConfig = FakeBuckConfig
        .builder()
        .setFilesystem(filesystem)
        .setSections(sections)
        .build();

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "");

    KnownBuildRuleTypes knownBuildRuleTypes2 = KnownBuildRuleTypes.createInstance(
        buckConfig,
        filesystem, processExecutor,
        new FakeAndroidDirectoryResolver());

    assertNotEquals(knownBuildRuleTypes1, knownBuildRuleTypes2);
  }

  @Test
  public void canSetDefaultPlatformToDefault() throws Exception {
    ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRoot());
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of("default_platform", "default"));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();

    // This would throw if "default" weren't available as a platform.
    KnownBuildRuleTypes.createBuilder(
        buckConfig,
        filesystem, createExecutor(),
        new FakeAndroidDirectoryResolver()).build();
  }

  @Test
  public void canOverrideMultipleHostPlatforms() throws Exception {
    ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRoot());
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx#linux-x86_64", ImmutableMap.of("cache_links", "true"),
        "cxx#macosx-x86_64", ImmutableMap.of("cache_links", "true"),
        "cxx#windows-x86_64", ImmutableMap.of("cache_links", "true"));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();

    // It should be legal to override multiple host platforms even though
    // only one will be practically used in a build.
    KnownBuildRuleTypes.createBuilder(
        buckConfig,
        filesystem, createExecutor(),
        new FakeAndroidDirectoryResolver()).build();
  }

  private ProcessExecutor createExecutor() throws IOException {
    Path javac = temporaryFolder.newExecutableFile();
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
