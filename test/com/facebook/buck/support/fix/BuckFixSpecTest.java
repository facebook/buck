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

package com.facebook.buck.support.fix;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class BuckFixSpecTest {

  public static BuckFixSpec specWithPaths =
      ImmutableBuckFixSpec.of(
          new BuildId("foo-bar-baz"),
          "run",
          ExitCode.FATAL_GENERIC.getCode(),
          ImmutableList.of("arg1", "@argfile"),
          ImmutableList.of("arg1", "arg2", "arg3"),
          true,
          Optional.empty(),
          ImmutableMap.of("foo", ImmutableList.of("bar/baz.py")),
          BuckFixSpec.getLogsMapping(
              Optional.of(Paths.get("log.log")),
              Optional.of(Paths.get("machine-log.log")),
              Optional.of(Paths.get("out.trace")),
              Optional.of(Paths.get("buckconfig.json"))));

  @Test
  public void seralizesAsExpected() throws IOException {

    BuckFixSpec specWithoutPaths =
        ImmutableBuckFixSpec.of(
            new BuildId("foo-bar-baz"),
            "run",
            ExitCode.FATAL_GENERIC.getCode(),
            ImmutableList.of("arg1", "@argfile"),
            ImmutableList.of("arg1", "arg2", "arg3"),
            false,
            Optional.empty(),
            ImmutableMap.of(),
            BuckFixSpec.getLogsMapping(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));

    String expectedWithPaths =
        "{"
            + "    \"build_id\": \"foo-bar-baz\","
            + "    \"command\": \"run\","
            + "    \"exit_code\": 10,"
            + "    \"user_args\": [\"arg1\", \"@argfile\"],"
            + "    \"expanded_args\": [\"arg1\", \"arg2\", \"arg3\"],"
            + "    \"logs\": {"
            + "        \"main_log\": \"log.log\","
            + "        \"machine_log\": \"machine-log.log\","
            + "        \"trace_file\": \"out.trace\","
            + "        \"buckconfig\": \"buckconfig.json\""
            + "    },"
            + "    \"manually_invoked\": true,"
            + "    \"command_data\": null,"
            + "    \"buck_provided_scripts\": {\"foo\": [\"bar/baz.py\"]}"
            + "}";

    String expectedWithoutPaths =
        "{"
            + "    \"build_id\": \"foo-bar-baz\","
            + "    \"command\": \"run\","
            + "    \"exit_code\": 10,"
            + "    \"user_args\": [\"arg1\", \"@argfile\"],"
            + "    \"expanded_args\": [\"arg1\", \"arg2\", \"arg3\"],"
            + "    \"logs\": {"
            + "        \"main_log\": null,"
            + "        \"machine_log\": null,"
            + "        \"trace_file\": null,"
            + "        \"buckconfig\": null"
            + "    },"
            + "    \"manually_invoked\": false,"
            + "    \"command_data\": null,"
            + "    \"buck_provided_scripts\": {}"
            + "}";

    assertEquals(
        ObjectMappers.READER.readTree(expectedWithPaths),
        ObjectMappers.READER.readTree(ObjectMappers.WRITER.writeValueAsString(specWithPaths)));

    assertEquals(
        ObjectMappers.READER.readTree(expectedWithoutPaths),
        ObjectMappers.READER.readTree(ObjectMappers.WRITER.writeValueAsString(specWithoutPaths)));
  }
}
