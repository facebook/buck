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

package com.facebook.buck.external.parser;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.downwardapi.utils.DownwardApiConstants;
import com.facebook.buck.external.constants.ExternalBinaryBuckConstants;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ParsedEnvVarsTest {

  @Rule public final ExpectedException exception = ExpectedException.none();

  @Test
  public void throwsIfMissingEnv() {
    exception.expect(NullPointerException.class);
    exception.expectMessage("Missing env var: BUCK_VERBOSITY");
    ParsedEnvVars.parse(ImmutableMap.of());
  }

  @Test
  public void canReadEnvs() {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builderWithExpectedSize(6);
    builder.put(DownwardApiConstants.ENV_VERBOSITY, "STANDARD_INFORMATION");
    builder.put(DownwardApiConstants.ENV_ANSI_ENABLED, "true");
    builder.put(DownwardApiConstants.ENV_ACTION_ID, "my_action");
    builder.put(DownwardApiConstants.ENV_BUILD_UUID, "my_build");
    builder.put(DownwardApiConstants.ENV_EVENT_PIPE, "my_pipe");
    builder.put(
        ExternalBinaryBuckConstants.ENV_RULE_CELL_ROOT,
        Paths.get("abs_path").toAbsolutePath().toString());
    ImmutableMap<String, String> envs = builder.build();

    ParsedEnvVars parsedEnvVars = ParsedEnvVars.parse(envs);
    assertThat(parsedEnvVars.getVerbosity(), equalTo(Verbosity.STANDARD_INFORMATION));
    assertTrue(parsedEnvVars.isAnsiTerminal());
    assertThat(parsedEnvVars.getActionId(), equalTo("my_action"));
    assertThat(parsedEnvVars.getBuildUuid(), equalTo(new BuildId("my_build")));
    assertThat(parsedEnvVars.getEventPipe(), equalTo(Paths.get("my_pipe")));
    assertThat(
        parsedEnvVars.getRuleCellRoot(),
        equalTo(AbsPath.of(Paths.get("abs_path").toAbsolutePath())));
  }
}
