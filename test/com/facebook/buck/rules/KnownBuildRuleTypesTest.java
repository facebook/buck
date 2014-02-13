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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidLibraryRule;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


public class KnownBuildRuleTypesTest {

  @ClassRule public static TemporaryFolder folder = new TemporaryFolder();
  @Rule public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  private static BuildRuleFactoryParams params;

  @BeforeClass
  public static void setupBuildParams() throws IOException {
    ProjectFilesystem filesystem = new ProjectFilesystem(folder.getRoot());
    params = new BuildRuleFactoryParams(
        new HashMap<String, Object>(),
        filesystem,
        BuildFileTree.constructBuildFileTree(filesystem),
        new BuildTargetParser(filesystem),
        BuildTargetFactory.newInstance("//:foo"),
        new FakeRuleKeyBuilderFactory(),
        true
    );
  }

  @Test
  public void whenJavacIsNotSetInBuckConfigConfiguredRulesCreateDefaultJavaLibraryBuildRuleWithAbsentJavac() throws IOException, NoSuchBuildTargetException {
    FakeBuckConfig buckConfig = new FakeBuckConfig();

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createConfiguredBuilder(buckConfig,
        new FakeProcessExecutor(), Optional.<String>absent()).build();
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(BuildRuleType.JAVA_LIBRARY);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    assertTrue("Rule is DefaultJavaLibraryRule", rule instanceof DefaultJavaLibraryRule);
    DefaultJavaLibraryRule libraryRule = (DefaultJavaLibraryRule) rule;
    assertEquals(Optional.absent(), libraryRule.getJavac());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateDefaultJavaLibraryBuildRuleWithJavacSet() throws IOException, NoSuchBuildTargetException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        new HashMap<String, Map<String, String>>() {{
          put("tools", new HashMap<String, String>() {{
            put("javac", javac.toString());
          }});
        }});

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createConfiguredBuilder(buckConfig,
        new FakeProcessExecutor(), Optional.<String>absent()).build();
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(BuildRuleType.JAVA_LIBRARY);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    assertTrue("Rule is DefaultJavaLibraryRule", rule instanceof DefaultJavaLibraryRule);
    DefaultJavaLibraryRule libraryRule = (DefaultJavaLibraryRule) rule;
    assertEquals(javac.toPath(), libraryRule.getJavac().get());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateDefaultJavaLibraryBuildRuleWithDifferentRuleKey() throws IOException, NoSuchBuildTargetException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        new HashMap<String, Map<String, String>>() {{
          put("tools", new HashMap<String, String>() {{
            put("javac", javac.toString());
          }});
        }});

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.getDefault();
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(BuildRuleType.JAVA_LIBRARY);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    KnownBuildRuleTypes configuredBuildRuleTypes = KnownBuildRuleTypes.createConfiguredBuilder(buckConfig,
        new FakeProcessExecutor(0, "fakeVersion 0.1", ""), Optional.<String>absent()).build();
    BuildRuleFactory<?> configuredFactory = configuredBuildRuleTypes.getFactory(BuildRuleType.JAVA_LIBRARY);
    BuildRule configuredRule = configuredFactory.newInstance(params).build(new BuildRuleResolver());

    assertNotEquals(rule.getRuleKey(), configuredRule.getRuleKey());
  }

  @Test(expected = HumanReadableException.class)
  public void whenJavacWithoutVersionSupportIsSetInBuckCreateConfiguredBuildRulesThrowsException() throws IOException, NoSuchBuildTargetException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        new HashMap<String, Map<String, String>>() {{
          put("tools", new HashMap<String, String>() {{
            put("javac", javac.toString());
          }});
        }});

    KnownBuildRuleTypes configuredBuildRuleTypes = KnownBuildRuleTypes.createConfiguredBuilder(buckConfig,
        new FakeProcessExecutor(1, "", "error"), Optional.<String>absent()).build();
    BuildRuleFactory<?> configuredFactory = configuredBuildRuleTypes.getFactory(BuildRuleType.JAVA_LIBRARY);
    configuredFactory.newInstance(params).build(new BuildRuleResolver());
  }

  @Test
  public void whenJavacIsNotSetInBuckConfigConfiguredRulesCreateAndroidLibraryBuildRuleWithAbsentJavac() throws IOException, NoSuchBuildTargetException {
    FakeBuckConfig buckConfig = new FakeBuckConfig();

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createConfiguredBuilder(buckConfig,
        new FakeProcessExecutor(), Optional.<String>absent()).build();
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(BuildRuleType.ANDROID_LIBRARY);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    assertTrue("Rule is AndroidLibraryRule", rule instanceof AndroidLibraryRule);
    AndroidLibraryRule libraryRule = (AndroidLibraryRule) rule;
    assertEquals(Optional.absent(), libraryRule.getJavac());
  }

  @Test
  public void whenJavacIsSetInBuckConfigConfiguredRulesCreateAndroidLibraryBuildRuleWithJavacSet() throws IOException, NoSuchBuildTargetException {
    final File javac = temporaryFolder.newFile();
    javac.setExecutable(true);

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        new HashMap<String, Map<String, String>>() {{
          put("tools", new HashMap<String, String>() {{
            put("javac", javac.toString());
          }});
        }});

    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.createConfiguredBuilder(buckConfig,
        new FakeProcessExecutor(), Optional.<String>absent()).build();
    BuildRuleFactory<?> factory = buildRuleTypes.getFactory(BuildRuleType.ANDROID_LIBRARY);
    BuildRule rule = factory.newInstance(params).build(new BuildRuleResolver());

    assertTrue("Rule is AndroidLibraryRule", rule instanceof AndroidLibraryRule);
    AndroidLibraryRule libraryRule = (AndroidLibraryRule) rule;
    assertEquals(javac.toPath(), libraryRule.getJavac().get());
  }
}
