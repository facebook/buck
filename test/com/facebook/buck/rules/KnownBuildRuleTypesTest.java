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

import static com.facebook.buck.java.JavaCompilerEnvironment.TARGETED_JAVA_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.android.AndroidLibrary;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.java.DefaultJavaLibrary;
import com.facebook.buck.java.JavaBuckConfig;
import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavacVersion;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;


public class KnownBuildRuleTypesTest {

  @ClassRule public static TemporaryFolder folder = new TemporaryFolder();
  @Rule public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  private static BuildRuleParams buildRuleParams;

  private static class TestDescription implements Description<ConstructorArg> {

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
    public ConstructorArg createUnpopulatedConstructorArg() {
      return new ConstructorArg() {};
    }

    @Override
    public <A extends ConstructorArg> BuildRule createBuildRule(
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
    arg.proguardConfig = Optional.absent();
    arg.annotationProcessorDeps = Optional.of(ImmutableSortedSet.<BuildRule>of());
    arg.annotationProcessorParams = Optional.of(ImmutableList.<String>of());
    arg.annotationProcessors = Optional.of(ImmutableSet.<String>of());
    arg.annotationProcessorOnly = Optional.absent();
    arg.postprocessClassesCommands = Optional.of(ImmutableList.<String>of());
    arg.resourcesRoot = Optional.absent();
    arg.providedDeps = Optional.of(ImmutableSortedSet.<BuildRule>of());
    arg.exportedDeps = Optional.of(ImmutableSortedSet.<BuildRule>of());
    arg.deps = Optional.of(ImmutableSortedSet.<BuildRule>of());
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
      throws IOException, NoSuchBuildTargetException {
    FakeBuckConfig buckConfig = new FakeBuckConfig();

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        JavaCompilerEnvironment.DEFAULT).build();
    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);
    assertEquals(Optional.<String> absent(), libraryRule.getJavac());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithJavacSet()
      throws IOException, NoSuchBuildTargetException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);
    JavaBuckConfig javaConfig = new JavaBuckConfig(buckConfig);

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        new JavaCompilerEnvironment(
            javaConfig.getJavac(),
            Optional.<JavacVersion>absent(),
            TARGETED_JAVA_VERSION,
            TARGETED_JAVA_VERSION))
        .build();

    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);
    assertEquals(javac.toPath(), libraryRule.getJavac().get());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithJavacVersionSet()
      throws IOException, NoSuchBuildTargetException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    JavacVersion javacVersion = new JavacVersion("fakeVersion 0.1");

    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        new JavaCompilerEnvironment(
            Optional.<Path>absent(),
            Optional.of(javacVersion),
            TARGETED_JAVA_VERSION,
            TARGETED_JAVA_VERSION))
        .build();
    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);
    assertEquals(javacVersion, libraryRule.getJavacVersion().get());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithDifferentRuleKey()
      throws IOException, NoSuchBuildTargetException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);

    KnownBuildRuleTypes buildRuleTypes =
        DefaultKnownBuildRuleTypes.getDefaultKnownBuildRuleTypes(new FakeProjectFilesystem());
    DefaultJavaLibrary libraryRule = createJavaLibrary(buildRuleTypes);

    KnownBuildRuleTypes configuredBuildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        new JavaCompilerEnvironment(
            Optional.<Path>absent(),
            Optional.of(new JavacVersion("fakeVersion 0.1")),
            TARGETED_JAVA_VERSION,
            TARGETED_JAVA_VERSION))
        .build();
    DefaultJavaLibrary configuredRule = createJavaLibrary(configuredBuildRuleTypes);

    assertNotEquals(libraryRule.getRuleKey(), configuredRule.getRuleKey());
  }

  @Test
  public void differentExternalJavacCreateJavaLibraryRulesWithDifferentRuleKey()
      throws IOException, NoSuchBuildTargetException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);
    JavaBuckConfig javaConfig = new JavaBuckConfig(buckConfig);

    KnownBuildRuleTypes configuredBuildRuleTypes1 = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        new JavaCompilerEnvironment(
            javaConfig.getJavac(),
            Optional.of(new JavacVersion("fakeVersion 0.1")),
            TARGETED_JAVA_VERSION,
            TARGETED_JAVA_VERSION))
        .build();
    DefaultJavaLibrary configuredRule1 = createJavaLibrary(configuredBuildRuleTypes1);

    KnownBuildRuleTypes configuredBuildRuleTypes2 = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        new JavaCompilerEnvironment(
            javaConfig.getJavac(),
            Optional.of(new JavacVersion("fakeVersion 0.2")),
            TARGETED_JAVA_VERSION,
            TARGETED_JAVA_VERSION))
        .build();
    DefaultJavaLibrary configuredRule2 = createJavaLibrary(configuredBuildRuleTypes2);

    assertNotEquals(configuredRule1.getRuleKey(), configuredRule2.getRuleKey());
  }

  @Test
  public void whenJavacIsNotSetInBuckConfigConfiguredRulesCreateAndroidLibraryRuleWithAbsentJavac()
      throws IOException, NoSuchBuildTargetException {
    FakeBuckConfig buckConfig = new FakeBuckConfig();

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        JavaCompilerEnvironment.DEFAULT).build();
    AndroidLibraryDescription description =
        (AndroidLibraryDescription) buildRuleTypes.getDescription(AndroidLibraryDescription.TYPE);

    AndroidLibraryDescription.Arg arg = new AndroidLibraryDescription.Arg();
    populateJavaArg(arg);
    arg.manifest = Optional.absent();
    AndroidLibrary rule = description.createBuildRule(
        buildRuleParams,
        new BuildRuleResolver(),
        arg);
    assertEquals(Optional.absent(), rule.getJavac());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateAndroidLibraryBuildRuleWithJavacSet()
      throws IOException, NoSuchBuildTargetException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    Map<String, Map<String, String>> sections = ImmutableMap.of(
        "tools", (Map<String, String>) ImmutableMap.of("javac", javac.toString()));
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);
    JavaBuckConfig javaConfig = new JavaBuckConfig(buckConfig);

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        new JavaCompilerEnvironment(
            javaConfig.getJavac(),
            Optional.<JavacVersion>absent(),
            TARGETED_JAVA_VERSION,
            TARGETED_JAVA_VERSION))
        .build();
    AndroidLibraryDescription description =
        (AndroidLibraryDescription) buildRuleTypes.getDescription(AndroidLibraryDescription.TYPE);

    AndroidLibraryDescription.Arg arg = new AndroidLibraryDescription.Arg();
    populateJavaArg(arg);
    arg.manifest = Optional.absent();
    AndroidLibrary rule = description.createBuildRule(
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
}
