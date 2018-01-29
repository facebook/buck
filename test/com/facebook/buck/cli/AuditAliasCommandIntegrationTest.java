/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AuditAliasCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws InterruptedException, IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "alias", tmp);
    workspace.setUp();

    // Make sure override does not appear in list twice.
    workspace.addBuckConfigLocalOption("alias", "foo", "//:bar_example");
    // Make sure output is union of .buckconfig and .buckconfig.local.
    workspace.addBuckConfigLocalOption("alias", "bar_ex", "//:bar_example");
  }

  @Test
  public void testBuckAliasList() throws IOException {
    ProcessResult result = workspace.runBuckCommand("audit", "alias", "--list");
    result.assertSuccess();

    // Remove trailing newline from stdout before passing to Splitter.
    String stdout = result.getStdout();
    assertTrue(stdout.endsWith("\n"));
    stdout = stdout.substring(0, stdout.length() - 1);

    List<String> aliases = Splitter.on('\n').splitToList(stdout);
    assertEquals(
        "Aliases that appear in both .buckconfig and .buckconfig.local should appear only once.",
        3,
        aliases.size());
    assertEquals(ImmutableSet.of("foo", "bar", "bar_ex"), ImmutableSet.copyOf(aliases));
  }

  @Test
  public void testBuckAliasListMap() throws IOException {
    ProcessResult result = workspace.runBuckCommand("audit", "alias", "--list-map");
    result.assertSuccess();

    // Remove trailing newline from stdout before passing to Splitter.
    String stdout = result.getStdout();
    assertTrue(stdout.endsWith("\n"));
    stdout = stdout.substring(0, stdout.length() - 1);

    List<String> aliases = Splitter.on('\n').splitToList(stdout);
    assertEquals(
        "Aliases that appear in both .buckconfig and .buckconfig.local should appear only once.",
        3,
        aliases.size());
    assertEquals(
        ImmutableSet.of("foo = //:bar_example", "bar = //:bar_example", "bar_ex = //:bar_example"),
        ImmutableSet.copyOf(aliases));
  }
}
