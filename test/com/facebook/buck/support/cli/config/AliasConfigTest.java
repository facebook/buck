/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.support.cli.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.core.config.BuckConfigTestUtils;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AliasConfigTest {
  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testGetAliasesThrowsForMalformedBuildTarget() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join("[alias]", "release   = :app_release"));
    AliasConfig buckConfig =
        BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader, AliasConfig.class);
    try {
      buckConfig.getAliases();
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals(
          "When parsing :app_release: relative path is not allowed.",
          e.getHumanReadableErrorMessage());
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
