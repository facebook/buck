/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class DefaultJavaLibraryRulesIntegrationTest {
  public static class Params {
    public final String abiGenerationMode;
    public final String sourceAbiVerificationMode;

    public Params(String abiGenerationMode, String sourceAbiVerificationMode) {
      this.abiGenerationMode = abiGenerationMode;
      this.sourceAbiVerificationMode = sourceAbiVerificationMode;
    }

    @Override
    public String toString() {
      return String.format("%s (verification = %s)", abiGenerationMode, sourceAbiVerificationMode);
    }
  }

  @Parameters(name = "{0}")
  public static Params[] getParams() {
    return new Params[] {
      new Params("class", "off"),
      new Params("source", "off"),
      new Params("source", "fail"),
      new Params("source_only", "off"),
      new Params("source_only", "fail"),
    };
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Parameterized.Parameter public Params params;

  @Test
  public void testBuildClass() throws IOException {
    assumeTrue(
        params.abiGenerationMode.equals("class")
            || params.sourceAbiVerificationMode.equals("fail"));

    testBuildTarget("//:main#class-abi");
  }

  @Test
  public void testBuildLibrary() throws IOException {
    testBuildTarget("//:main");
  }

  @Test
  public void testBuildSource() throws IOException {
    assumeTrue(
        params.abiGenerationMode.equals("source")
            || params.abiGenerationMode.equals("source_only")
            || params.sourceAbiVerificationMode.equals("fail"));

    if (params.abiGenerationMode.equals("source")) {
      testBuildTarget("//:main#source-abi");
    } else if (params.abiGenerationMode.equals("source_only")) {
      testBuildTarget("//:main#source-only-abi");
    }
  }

  @Test
  public void testBuildVerified() throws IOException {
    assumeTrue(params.sourceAbiVerificationMode.equals("fail"));

    testBuildTarget("//:main#verified-source-abi");
  }

  private void testBuildTarget(String target) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "source_abi", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild(
            "-c",
            String.format("java.abi_generation_mode=%s", params.abiGenerationMode),
            "-c",
            String.format("java.source_abi_verification_mode=%s", params.sourceAbiVerificationMode),
            target)
        .assertSuccess();
  }
}
