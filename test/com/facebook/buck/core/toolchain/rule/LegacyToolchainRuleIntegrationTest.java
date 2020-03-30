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

package com.facebook.buck.core.toolchain.rule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class LegacyToolchainRuleIntegrationTest {

  public @Rule TemporaryPaths tmp = new TemporaryPaths();

  private HttpdForTests.CapturingHttpHandler httpdHandler;
  private HttpdForTests httpd;

  private static final String cscDotSh = "#!/bin/sh\necho $1 > $2";
  private static final String cscDotBat = "echo %1 > %2";
  private String cscTarget = "";
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {
    cscTarget = Platform.detect().getType().isWindows() ? "//:dummy_csc.bat" : "//:dummy_csc.sh";
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "legacy_toolchain", tmp);
    workspace.addBuckConfigLocalOption("dotnet", "csc", cscTarget);
    workspace.addBuckConfigLocalOption("parser", "default_build_file_syntax", "skylark");
    workspace.addBuckConfigLocalOption("parser", "user_defined_rules", "enabled");
    workspace.addBuckConfigLocalOption("rule_analysis", "mode", "PROVIDER_COMPATIBLE");
    workspace.addBuckConfigLocalOption("download", "in_build", "true");

    httpdHandler =
        new HttpdForTests.CapturingHttpHandler(
            ImmutableMap.<String, byte[]>builder()
                .put("/csc.sh", cscDotSh.getBytes(Charsets.UTF_8))
                .put("/csc.bat", cscDotBat.getBytes(Charsets.UTF_8))
                .build());
    httpd = new HttpdForTests();
    httpd.addHandler(httpdHandler);
    httpd.start();
  }

  @After
  public void tearDown() throws Exception {
    httpd.close();
  }

  private void rewriteBuckFileTemplate() throws IOException {
    // Replace a few tokens with the real host and port where our server is running
    URI uri = httpd.getRootUri();
    workspace.writeContentsToPath(
        workspace
            .getFileContents("BUCK")
            .replace("<HOST>", uri.getHost())
            .replace("<PORT>", Integer.toString(uri.getPort())),
        "BUCK");
  }

  @Test
  public void addsCorrectDepsForConfigBasedToolchains() throws IOException {
    workspace.setUp();
    rewriteBuckFileTemplate();

    ProcessResult result =
        workspace.runBuckCommand("query", "//:dotnet", "--output-attribute", ".*").assertSuccess();
    JsonNode deps =
        ObjectMappers.READER
            .readTree(result.getStdout())
            .get("//:dotnet")
            .get("buck.direct_dependencies");

    assertNotNull(deps);
    assertTrue(deps.isArray());
    assertEquals(1, deps.size());
    assertEquals(cscTarget, deps.get(0).asText());
  }

  @Test
  public void canBeUsedByRuleAnalysisGraph() throws IOException {
    workspace.setUp();
    rewriteBuckFileTemplate();

    Path expectedPath =
        workspace.resolve(
            BuildPaths.getGenDir(
                    workspace.getProjectFileSystem(), BuildTargetFactory.newInstance("//:dummy"))
                .resolve("out.txt"));
    Path output = workspace.buildAndReturnOutput("//:dummy");

    assertEquals(expectedPath, output);
    assertEquals(
        "In.cs", workspace.getProjectFileSystem().readFileIfItExists(expectedPath).get().trim());
  }

  @Test
  public void failsOnInvalidToolchainName() throws IOException {
    workspace.setUp();
    assertThat(
        workspace.runBuckBuild("//invalid:invalid").assertFailure().getStderr(),
        Matchers.containsString("Unknown toolchain: invalid-toolchain"));
  }
}
