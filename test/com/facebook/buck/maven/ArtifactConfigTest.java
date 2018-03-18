/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.maven;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.Lists;
import java.io.IOException;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

public class ArtifactConfigTest {

  @Test
  public void shouldMergeCmdLineArgsCorrectly() throws IOException, CmdLineException {

    String jsonString =
        "{\"repositories\": [{\"url\":\"https://example.com\"}],"
            + "\"third_party\":\"tp0\","
            + "\"repo\":\"br\","
            + "\"visibility\":[\"r1\"],"
            + "\"artifacts\":[\"artifact1\"]}";

    ArtifactConfig base = ObjectMappers.readValue(jsonString, ArtifactConfig.class);

    ArtifactConfig.CmdLineArgs args = new ArtifactConfig.CmdLineArgs();
    CmdLineParser parser = new CmdLineParser(args);
    parser.parseArgument(
        "-third-party", "tp1", "-maven", "http://bar.co", "artifact2", "-visibility", "r2");

    base.mergeCmdLineArgs(args);
    assertEquals("tp1", base.thirdParty);
    assertEquals(base.artifacts, Lists.newArrayList("artifact1", "artifact2"));
    assertEquals(base.visibility, Lists.newArrayList("r1", "r2"));
    assertEquals("br", base.buckRepoRoot);
    assertEquals("https://example.com", base.repositories.get(0).getUrl());
    assertEquals("http://bar.co", base.repositories.get(1).getUrl());
  }
}
