/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.skylark.parser;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.google.common.base.Charsets;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class SkylarkUserDefinedRulesParserTest {
  private SkylarkProjectBuildFileParser parser;
  private ProjectFilesystem projectFilesystem;
  private SkylarkFilesystem skylarkFilesystem;
  private KnownRuleTypesProvider knownRuleTypesProvider;

  @Rule public ExpectedException thrown = ExpectedException.none();
  private Cell cell;

  @Before
  public void setUp() {
    projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    skylarkFilesystem = SkylarkFilesystem.using(projectFilesystem);
    cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    knownRuleTypesProvider = TestKnownRuleTypesProvider.create(pluginManager);
    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));
  }

  private SkylarkProjectBuildFileParser createParser(EventHandler eventHandler) {
    return SkylarkProjectBuildFileParserTestUtils.createParserWithOptions(
        skylarkFilesystem,
        eventHandler,
        SkylarkProjectBuildFileParserTestUtils.getDefaultParserOptions(cell, knownRuleTypesProvider)
            .setEnableUserDefinedRules(true)
            .build());
  }

  private Map<String, Object> getSingleRule(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    return SkylarkProjectBuildFileParserTestUtils.getSingleRule(parser, buildFile);
  }

  private void assertParserFails(
      EventCollector eventCollector,
      SkylarkProjectBuildFileParser parser,
      Path buildFile,
      String substring)
      throws IOException, InterruptedException {
    try {
      parser.getBuildFileManifest(buildFile);

    } catch (BuildFileParseException e) {
      Event event = eventCollector.iterator().next();
      assertEquals(EventKind.ERROR, event.getKind());
      assertThat(event.getMessage(), containsString(substring));
      throw e;
    }
  }

  @Test
  public void enablesLabelObjectIfConfigured() throws IOException, InterruptedException {
    Path extensionFile = projectFilesystem.resolve("foo.bzl");
    Path buildFile = projectFilesystem.resolve("BUCK");

    String extensionContents =
        "def foo():\n"
            + "    lbl = Label(\"@repo//package/sub:target\")\n"
            + "    native.genrule(\n"
            + "        name = \"foo\",\n"
            + "        cmd = \"echo \" + lbl.name  + \" > $OUT\",\n"
            + "        out = \"foo.out\"\n"
            + "    )\n";
    String buildContents = "load(\"//:foo.bzl\", \"foo\")\nfoo()\n";

    Files.write(buildFile, buildContents.getBytes(Charsets.UTF_8));
    Files.write(extensionFile, extensionContents.getBytes(Charsets.UTF_8));

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    assertEquals("echo target > $OUT", getSingleRule(buildFile).get("cmd"));
  }

  @Test
  public void enablesAttrsModuleIfConfigured() throws IOException, InterruptedException {
    Path extensionFile = projectFilesystem.resolve("foo.bzl");
    Path buildFile = projectFilesystem.resolve("BUCK");

    String extensionContents =
        "def foo():\n"
            + "    if repr(attr) != \"<attr>\":\n        fail(\"Expected attr module to exist\")";
    String buildContents = "load(\"//:foo.bzl\", \"foo\")\nfoo()\n";

    Files.write(buildFile, buildContents.getBytes(Charsets.UTF_8));
    Files.write(extensionFile, extensionContents.getBytes(Charsets.UTF_8));

    parser = createParser(new PrintingEventHandler(EventKind.ALL_EVENTS));

    parser.getBuildFileManifest(buildFile);
  }
}
