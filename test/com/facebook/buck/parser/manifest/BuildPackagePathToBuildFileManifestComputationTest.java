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
package com.facebook.buck.parser.manifest;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.core.graph.transformation.impl.FakeComputationEnvironment;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import org.junit.Test;

public class BuildPackagePathToBuildFileManifestComputationTest {

  @Test
  public void callsParserOnceWithResolvedPath() throws Exception {

    ProjectBuildFileParser parser = createMock(ProjectBuildFileParser.class);

    expect(parser.getBuildFileManifest(eq(Paths.get("/root/package/BUCK"))))
        .andReturn(createMock(BuildFileManifest.class))
        .once();
    replay(parser);

    BuildPackagePathToBuildFileManifestComputation computation =
        BuildPackagePathToBuildFileManifestComputation.of(
            parser, Paths.get("BUCK"), Paths.get("/root"));
    FakeComputationEnvironment env = new FakeComputationEnvironment(ImmutableMap.of());

    computation.transform(
        ImmutableBuildPackagePathToBuildFileManifestKey.of(Paths.get("package")), env);

    verify(parser);
  }
}
