/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AliasConfigTest {
  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  /**
   * Ensure that whichever alias is listed first in the file is the one used in the reverse map if
   * the value appears multiple times.
   */
  @Test
  public void testGetBasePathToAliasMap() throws IOException, NoSuchBuildTargetException {
    Reader reader1 =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[alias]",
                    "debug   =   //java/com/example:app_debug",
                    "release =   //java/com/example:app_release"));
    AliasConfig config1 =
        BuckConfigTestUtils.createWithDefaultFilesystem(
            temporaryFolder, reader1, AliasConfig.class);
    assertEquals(
        ImmutableMap.of(Paths.get("java/com/example"), "debug"), config1.getBasePathToAliasMap());
    assertEquals(
        ImmutableMap.of(
            "debug", "//java/com/example:app_debug",
            "release", "//java/com/example:app_release"),
        config1.getEntries());

    Reader reader2 =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[alias]",
                    "release =   //java/com/example:app_release",
                    "debug   =   //java/com/example:app_debug"));
    AliasConfig config2 =
        BuckConfigTestUtils.createWithDefaultFilesystem(
            temporaryFolder, reader2, AliasConfig.class);
    assertEquals(
        ImmutableMap.of(Paths.get("java/com/example"), "release"), config2.getBasePathToAliasMap());
    assertEquals(
        ImmutableMap.of(
            "debug", "//java/com/example:app_debug",
            "release", "//java/com/example:app_release"),
        config2.getEntries());

    Reader noAliasesReader = new StringReader("");
    AliasConfig noAliasesConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(
            temporaryFolder, noAliasesReader, AliasConfig.class);
    assertEquals(ImmutableMap.of(), noAliasesConfig.getBasePathToAliasMap());
    assertEquals(ImmutableMap.of(), noAliasesConfig.getEntries());
  }

  @Test
  public void testGetAliasesThrowsForMalformedBuildTarget() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join("[alias]", "release   = :app_release"));
    AliasConfig buckConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader, AliasConfig.class);
    try {
      buckConfig.getAliases();
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("Path in :app_release must start with //", e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testGetBuildTargetForAlias() throws IOException, NoSuchBuildTargetException {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[alias]",
                    "foo = //java/com/example:foo",
                    "bar = //java/com/example:bar",
                    "baz = //java/com/example:foo //java/com/example:bar",
                    "bash = "));
    AliasConfig config =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader, AliasConfig.class);

    assertEquals(
        ImmutableSet.of("//java/com/example:foo"), config.getBuildTargetForAliasAsString("foo"));
    assertEquals(
        ImmutableSet.of("//java/com/example:bar"), config.getBuildTargetForAliasAsString("bar"));
    assertEquals(
        ImmutableSet.of("//java/com/example:foo", "//java/com/example:bar"),
        config.getBuildTargetForAliasAsString("baz"));
    assertEquals(ImmutableSet.of(), config.getBuildTargetForAliasAsString("bash"));

    // Flavors on alias.
    assertEquals(
        ImmutableSet.of("//java/com/example:foo#src_jar"),
        config.getBuildTargetForAliasAsString("foo#src_jar"));
    assertEquals(
        ImmutableSet.of("//java/com/example:bar#fl1,fl2"),
        config.getBuildTargetForAliasAsString("bar#fl1,fl2"));
    assertEquals(
        ImmutableSet.of("//java/com/example:foo#fl1,fl2", "//java/com/example:bar#fl1,fl2"),
        config.getBuildTargetForAliasAsString("baz#fl1,fl2"));
    assertEquals(ImmutableSet.of(), config.getBuildTargetForAliasAsString("bash#fl1,fl2"));

    assertEquals(
        "Invalid alias names, such as build targets, should be tolerated by this method.",
        ImmutableSet.of(),
        config.getBuildTargetForAliasAsString("//java/com/example:foo"));
    assertEquals(ImmutableSet.of(), config.getBuildTargetForAliasAsString("notathing"));
    assertEquals(ImmutableSet.of(), config.getBuildTargetForAliasAsString("notathing#src_jar"));

    Reader noAliasesReader = new StringReader("");
    AliasConfig noAliasesConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(
            temporaryFolder, noAliasesReader, AliasConfig.class);
    assertEquals(ImmutableSet.of(), noAliasesConfig.getBuildTargetForAliasAsString("foo"));
    assertEquals(ImmutableSet.of(), noAliasesConfig.getBuildTargetForAliasAsString("bar"));
    assertEquals(ImmutableSet.of(), noAliasesConfig.getBuildTargetForAliasAsString("baz"));
  }

  /** Ensures that all public methods of BuckConfig return reasonable values for an empty config. */
  @Test
  public void testEmptyConfig() {
    AliasConfig emptyConfig = AliasConfig.from(FakeBuckConfig.builder().build());
    assertEquals(ImmutableMap.<String, String>of(), emptyConfig.getEntries());
    assertEquals(ImmutableSet.of(), emptyConfig.getBuildTargetForAliasAsString("fb4a"));
    assertEquals(ImmutableMap.<Path, String>of(), emptyConfig.getBasePathToAliasMap());
  }

  @Test
  public void testValidateAliasName() {
    AliasConfig.validateAliasName("f");
    AliasConfig.validateAliasName("_");
    AliasConfig.validateAliasName("F");
    AliasConfig.validateAliasName("fb4a");
    AliasConfig.validateAliasName("FB4A");
    AliasConfig.validateAliasName("FB4_");

    try {
      AliasConfig.validateAliasName("");
      fail("Should have thrown HumanReadableException");
    } catch (HumanReadableException e) {
      assertEquals("Alias cannot be the empty string.", e.getHumanReadableErrorMessage());
    }

    try {
      AliasConfig.validateAliasName("42meaningOfLife");
      fail("Should have thrown HumanReadableException");
    } catch (HumanReadableException e) {
      assertEquals("Not a valid alias: 42meaningOfLife.", e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testReferentialAliases() throws IOException, NoSuchBuildTargetException {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[alias]",
                    "foo            = //java/com/example:foo",
                    "bar            = //java/com/example:bar",
                    "foo_codename   = foo",
                    "",
                    "# Do not delete these: automation builds require these aliases to exist!",
                    "automation_foo = foo_codename",
                    "automation_bar = bar"));
    AliasConfig config =
        AliasConfig.from(BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader));
    assertEquals(
        ImmutableSet.of("//java/com/example:foo"), config.getBuildTargetForAliasAsString("foo"));
    assertEquals(
        ImmutableSet.of("//java/com/example:bar"), config.getBuildTargetForAliasAsString("bar"));
    assertEquals(
        ImmutableSet.of("//java/com/example:foo"),
        config.getBuildTargetForAliasAsString("foo_codename"));
    assertEquals(
        ImmutableSet.of("//java/com/example:foo"),
        config.getBuildTargetForAliasAsString("automation_foo"));
    assertEquals(
        ImmutableSet.of("//java/com/example:bar"),
        config.getBuildTargetForAliasAsString("automation_bar"));
    assertEquals(ImmutableSet.of(), config.getBuildTargetForAliasAsString("baz"));
  }

  @Test
  public void testUnresolvedAliasThrows() throws IOException, NoSuchBuildTargetException {
    Reader reader =
        new StringReader(
            Joiner.on('\n').join("[alias]", "foo = //java/com/example:foo", "bar = food"));
    AliasConfig buckConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader, AliasConfig.class);
    try {
      buckConfig.getAliases();
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("No alias for: food.", e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testDuplicateDefinitionsDefinitionOverride()
      throws IOException, NoSuchBuildTargetException {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join("[alias]", "foo = //java/com/example:foo", "foo = //java/com/example:bar"));
    AliasConfig config =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader, AliasConfig.class);
    assertEquals(
        ImmutableSet.of("//java/com/example:bar"), config.getBuildTargetForAliasAsString("foo"));
  }
}
