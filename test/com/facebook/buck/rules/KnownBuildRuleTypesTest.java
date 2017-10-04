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
import static org.junit.Assert.assertThat;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JavaBinaryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.ocaml.OcamlBinaryDescription;
import com.facebook.buck.ocaml.OcamlLibraryDescription;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.hamcrest.Matchers;
import org.immutables.value.Value;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class KnownBuildRuleTypesTest {

  @ClassRule public static TemporaryFolder folder = new TemporaryFolder();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private static final String FAKE_XCODE_DEV_PATH = "/Fake/Path/To/Xcode.app/Contents/Developer";
  private static final ImmutableMap<String, String> environment =
      ImmutableMap.copyOf(System.getenv());

  private static BuildTarget buildTarget;
  private static ProjectFilesystem projectFilesystem;
  private static BuildRuleParams buildRuleParams;

  static class KnownRuleTestDescription implements Description<KnownRuleTestDescriptionArg> {

    @BuckStyleImmutable
    @Value.Immutable
    interface AbstractKnownRuleTestDescriptionArg extends CommonDescriptionArg {}

    private final String value;

    private KnownRuleTestDescription(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    @Override
    public Class<KnownRuleTestDescriptionArg> getConstructorArgType() {
      return KnownRuleTestDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        TargetGraph targetGraph,
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        BuildRuleResolver resolver,
        CellPathResolver cellRoots,
        KnownRuleTestDescriptionArg args) {
      return null;
    }
  }

  @BeforeClass
  public static void setupBuildParams() throws IOException {
    projectFilesystem = new FakeProjectFilesystem();
    buildTarget = BuildTargetFactory.newInstance("//:foo");
    buildRuleParams = TestBuildRuleParams.create();
  }

  private DefaultJavaLibrary createJavaLibrary(KnownBuildRuleTypes buildRuleTypes)
      throws NoSuchBuildTargetException {
    JavaLibraryDescription description =
        (JavaLibraryDescription)
            buildRuleTypes.getDescription(
                Description.getBuildRuleType(JavaLibraryDescription.class));

    JavaLibraryDescriptionArg arg = JavaLibraryDescriptionArg.builder().setName("foo").build();
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    return (DefaultJavaLibrary)
        description.createBuildRule(
            TargetGraph.EMPTY,
            buildTarget,
            projectFilesystem,
            buildRuleParams,
            resolver,
            TestCellBuilder.createCellRoots(projectFilesystem),
            arg);
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

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("tools", ImmutableMap.of("javac", javac.toString()));
    BuckConfig buckConfig =
        FakeBuckConfig.builder().setFilesystem(filesystem).setSections(sections).build();

    KnownBuildRuleTypes buildRuleTypes =
        KnownBuildRuleTypesTestUtil.getDefaultKnownBuildRuleTypes(filesystem, environment);
    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "fakeVersion 0.1");
    KnownBuildRuleTypes configuredBuildRuleTypes =
        KnownBuildRuleTypesTestUtil.createInstance(buckConfig, filesystem, processExecutor);
    DefaultJavaLibrary configuredRule = createJavaLibrary(configuredBuildRuleTypes);

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new SingleThreadedBuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeFileHashCache hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(javac, MorePaths.asByteSource(javac).hash(Hashing.sha1())));
    RuleKey configuredKey =
        new DefaultRuleKeyFactory(0, hashCache, resolver, ruleFinder).build(configuredRule);
    RuleKey libraryKey =
        new DefaultRuleKeyFactory(0, hashCache, resolver, ruleFinder).build(libraryRule);

    assertNotEquals(libraryKey, configuredKey);
  }

  @Test
  public void whenRegisteringDescriptionsLastOneWins() throws Exception {
    FlavorDomain<CxxPlatform> cxxPlatforms = FlavorDomain.of("C/C++ platform");
    CxxPlatform defaultPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;

    KnownBuildRuleTypes.Builder buildRuleTypesBuilder = KnownBuildRuleTypes.builder();
    buildRuleTypesBuilder.register(new KnownRuleTestDescription("Foo"));
    buildRuleTypesBuilder.register(new KnownRuleTestDescription("Bar"));
    buildRuleTypesBuilder.register(new KnownRuleTestDescription("Raz"));

    buildRuleTypesBuilder.setCxxPlatforms(cxxPlatforms);
    buildRuleTypesBuilder.setDefaultCxxPlatform(defaultPlatform);

    KnownBuildRuleTypes buildRuleTypes = buildRuleTypesBuilder.build();

    assertEquals(
        "Only one description should have wound up in the final KnownBuildRuleTypes",
        KnownBuildRuleTypes.builder()
                .setCxxPlatforms(cxxPlatforms)
                .setDefaultCxxPlatform(defaultPlatform)
                .build()
                .getAllDescriptions()
                .size()
            + 1,
        buildRuleTypes.getAllDescriptions().size());

    boolean foundTestDescription = false;
    for (Description<?> description : buildRuleTypes.getAllDescriptions()) {
      if (Description.getBuildRuleType(description)
          .equals(Description.getBuildRuleType(KnownRuleTestDescription.class))) {
        assertFalse("Should only find one test description", foundTestDescription);
        foundTestDescription = true;
        assertEquals(
            "Last description should have won",
            "Raz",
            ((KnownRuleTestDescription) description).getValue());
      }
    }
  }

  @Test
  public void createInstanceShouldReturnDifferentInstancesIfCalledWithDifferentParameters()
      throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    KnownBuildRuleTypes knownBuildRuleTypes1 =
        KnownBuildRuleTypesTestUtil.createInstance(
            FakeBuckConfig.builder().build(), filesystem, createExecutor());

    final Path javac = temporaryFolder.newExecutableFile();
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("tools", ImmutableMap.of("javac", javac.toString()));
    BuckConfig buckConfig =
        FakeBuckConfig.builder().setFilesystem(filesystem).setSections(sections).build();

    ProcessExecutor processExecutor = createExecutor(javac.toString(), "");

    KnownBuildRuleTypes knownBuildRuleTypes2 =
        KnownBuildRuleTypesTestUtil.createInstance(buckConfig, filesystem, processExecutor);

    assertNotEquals(knownBuildRuleTypes1, knownBuildRuleTypes2);
  }

  @Test
  public void canSetDefaultPlatformToDefault() throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx", ImmutableMap.of("default_platform", "default"));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();

    // This would throw if "default" weren't available as a platform.
    KnownBuildRuleTypesTestUtil.createInstance(buckConfig, filesystem, createExecutor());
  }

  @Test
  public void canOverrideMultipleHostPlatforms() throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of(
            "cxx#linux-x86_64", ImmutableMap.of("cache_links", "true"),
            "cxx#macosx-x86_64", ImmutableMap.of("cache_links", "true"),
            "cxx#windows-x86_64", ImmutableMap.of("cache_links", "true"));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();

    // It should be legal to override multiple host platforms even though
    // only one will be practically used in a build.
    KnownBuildRuleTypesTestUtil.createInstance(buckConfig, filesystem, createExecutor());
  }

  @Test
  public void canOverrideDefaultHostPlatform() throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    Flavor flavor = InternalFlavor.of("flavor");
    String flag = "-flag";
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of("cxx#" + flavor, ImmutableMap.of("cflags", flag));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    KnownBuildRuleTypes knownBuildRuleTypes =
        KnownBuildRuleTypesTestUtil.createInstance(buckConfig, filesystem, createExecutor());
    assertThat(
        knownBuildRuleTypes.getCxxPlatforms().getValue(flavor).getCflags(),
        Matchers.contains(flag));
  }

  @Test
  public void ocamlUsesConfiguredDefaultPlatform() throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    Flavor flavor = InternalFlavor.of("flavor");
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of(
            "cxx",
            ImmutableMap.of("default_platform", flavor.toString()),
            "cxx#" + flavor,
            ImmutableMap.of());
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    KnownBuildRuleTypes knownBuildRuleTypes =
        KnownBuildRuleTypesTestUtil.createInstance(buckConfig, filesystem, createExecutor());
    OcamlLibraryDescription ocamlLibraryDescription =
        (OcamlLibraryDescription)
            knownBuildRuleTypes.getDescription(
                knownBuildRuleTypes.getBuildRuleType("ocaml_library"));
    assertThat(
        ocamlLibraryDescription.getOcamlBuckConfig().getCxxPlatform(),
        Matchers.equalTo(knownBuildRuleTypes.getCxxPlatforms().getValue(flavor)));
    OcamlBinaryDescription ocamlBinaryDescription =
        (OcamlBinaryDescription)
            knownBuildRuleTypes.getDescription(
                knownBuildRuleTypes.getBuildRuleType("ocaml_binary"));
    assertThat(
        ocamlBinaryDescription.getOcamlBuckConfig().getCxxPlatform(),
        Matchers.equalTo(knownBuildRuleTypes.getCxxPlatforms().getValue(flavor)));
  }

  @Test
  public void javaDefaultCxxPlatform() throws Exception {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    Flavor flavor = InternalFlavor.of("flavor");
    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of(
            "cxx#" + flavor,
            ImmutableMap.of(),
            "java",
            ImmutableMap.of("default_cxx_platform", flavor.toString()));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    KnownBuildRuleTypes knownBuildRuleTypes =
        KnownBuildRuleTypesTestUtil.createInstance(buckConfig, filesystem, createExecutor());
    JavaBinaryDescription javaBinaryDescription =
        (JavaBinaryDescription)
            knownBuildRuleTypes.getDescription(knownBuildRuleTypes.getBuildRuleType("java_binary"));
    assertThat(
        javaBinaryDescription.getDefaultCxxPlatform(),
        Matchers.equalTo(knownBuildRuleTypes.getCxxPlatforms().getValue(flavor)));
    JavaTestDescription javaTestDescription =
        (JavaTestDescription)
            knownBuildRuleTypes.getDescription(knownBuildRuleTypes.getBuildRuleType("java_test"));
    assertThat(
        javaTestDescription.getDefaultCxxPlatform(),
        Matchers.equalTo(knownBuildRuleTypes.getCxxPlatforms().getValue(flavor)));
  }

  private ProcessExecutor createExecutor() throws IOException {
    Path javac = temporaryFolder.newExecutableFile();
    return createExecutor(javac.toString(), "");
  }

  private ProcessExecutor createExecutor(String javac, String version) {
    Map<ProcessExecutorParams, FakeProcess> processMap = new HashMap<>();

    FakeProcess process = new FakeProcess(0, "", version);
    ProcessExecutorParams params =
        ProcessExecutorParams.builder().setCommand(ImmutableList.of(javac, "-version")).build();
    processMap.put(params, process);

    addXcodeSelectProcess(processMap, FAKE_XCODE_DEV_PATH);

    processMap.putAll(
        KnownBuildRuleTypesTestUtil.getPythonProcessMap(
            KnownBuildRuleTypesTestUtil.getPaths(environment)));

    return new FakeProcessExecutor(processMap);
  }

  private static void addXcodeSelectProcess(
      Map<ProcessExecutorParams, FakeProcess> processMap, String xcodeSelectPath) {

    FakeProcess xcodeSelectOutputProcess = new FakeProcess(0, xcodeSelectPath, "");
    ProcessExecutorParams xcodeSelectParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("xcode-select", "--print-path"))
            .build();
    processMap.put(xcodeSelectParams, xcodeSelectOutputProcess);
  }
}
