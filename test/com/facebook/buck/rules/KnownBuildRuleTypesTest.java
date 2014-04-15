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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidLibrary;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.java.DefaultJavaLibrary;
import com.facebook.buck.java.JavaBuckConfig;
import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavacVersion;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

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

  private static BuildRuleFactoryParams params;

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
    public Buildable createBuildable(
        BuildRuleParams params, ConstructorArg args) {
      return null;
    }
  }

  @BeforeClass
  public static void setupBuildParams() throws IOException {
    ProjectFilesystem filesystem = new ProjectFilesystem(folder.getRoot());
    params = new BuildRuleFactoryParams(
        Maps.<String, Object>newHashMap(),
        filesystem,
        BuildFileTree.constructBuildFileTree(filesystem),
        new BuildTargetParser(filesystem),
        BuildTargetFactory.newInstance("//:foo"),
        new FakeRuleKeyBuilderFactory(),
        true);
  }

  @Test
  public void whenJavacIsNotSetInBuckConfigConfiguredRulesCreateJavaLibraryRuleWithAbsentJavac()
      throws IOException, NoSuchBuildTargetException {
    FakeBuckConfig buckConfig = new FakeBuckConfig();

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        JavaCompilerEnvironment.DEFAULT).build();
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(JavaLibraryDescription.TYPE);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    assertTrue("Rule is DefaultJavaLibraryRule", rule.getBuildable() instanceof DefaultJavaLibrary);
    DefaultJavaLibrary libraryRule = (DefaultJavaLibrary) rule.getBuildable();
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
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(JavaLibraryDescription.TYPE);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    assertTrue("Rule is DefaultJavaLibraryRule", rule.getBuildable() instanceof DefaultJavaLibrary);
    DefaultJavaLibrary libraryRule = (DefaultJavaLibrary) rule.getBuildable();
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
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(JavaLibraryDescription.TYPE);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    DefaultJavaLibrary libraryRule = (DefaultJavaLibrary) rule.getBuildable();
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
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(JavaLibraryDescription.TYPE);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    KnownBuildRuleTypes configuredBuildRuleTypes = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        new JavaCompilerEnvironment(
            Optional.<Path>absent(),
            Optional.of(new JavacVersion("fakeVersion 0.1")),
            TARGETED_JAVA_VERSION,
            TARGETED_JAVA_VERSION))
        .build();
    BuildRuleFactory<?> configuredFactory =
        configuredBuildRuleTypes.getFactory(JavaLibraryDescription.TYPE);
    BuildRule configuredRule = configuredFactory.newInstance(params).build(new BuildRuleResolver());

    assertNotEquals(rule.getRuleKey(), configuredRule.getRuleKey());
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
    BuildRuleFactory<?> configuredFactory1 =
        configuredBuildRuleTypes1.getFactory(JavaLibraryDescription.TYPE);
    BuildRule configuredRule1 = configuredFactory1.newInstance(params)
        .build(new BuildRuleResolver());

    KnownBuildRuleTypes configuredBuildRuleTypes2 = KnownBuildRuleTypes.createBuilder(
        buckConfig,
        new FakeAndroidDirectoryResolver(),
        new JavaCompilerEnvironment(
            javaConfig.getJavac(),
            Optional.of(new JavacVersion("fakeVersion 0.2")),
            TARGETED_JAVA_VERSION,
            TARGETED_JAVA_VERSION))
        .build();
    BuildRuleFactory<?> configuredFactory2 =
        configuredBuildRuleTypes2.getFactory(JavaLibraryDescription.TYPE);
    BuildRule configuredRule2 = configuredFactory2.newInstance(params)
        .build(new BuildRuleResolver());

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
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(AndroidLibraryDescription.TYPE);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    assertTrue("Rule is AndroidLibraryRule", rule.getBuildable() instanceof AndroidLibrary);
    AndroidLibrary libraryRule = (AndroidLibrary) rule.getBuildable();
    assertEquals(Optional.absent(), libraryRule.getJavac());
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
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(AndroidLibraryDescription.TYPE);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    assertTrue("Rule is AndroidLibraryRule", rule.getBuildable() instanceof AndroidLibrary);
    AndroidLibrary libraryRule = (AndroidLibrary) rule.getBuildable();
    assertEquals(javac.toPath(), libraryRule.getJavac().get());
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
