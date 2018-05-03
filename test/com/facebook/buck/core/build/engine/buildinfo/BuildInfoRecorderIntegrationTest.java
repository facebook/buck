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

package com.facebook.buck.core.build.engine.buildinfo;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.DirArtifactCacheTestUtil;
import com.facebook.buck.artifact_cache.TestArtifactCaches;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class BuildInfoRecorderIntegrationTest {
  private static final String RULE_KEY = Strings.repeat("a", 40);

  private static final BuildTarget BUILD_TARGET = BuildTargetFactory.newInstance("//foo:bar");

  @Test
  public void testPerformUploadToArtifactCache() throws IOException, InterruptedException {
    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(new FakeProjectFilesystem());
    Path cacheDir = Files.createTempDirectory("root");
    ArtifactCache artifactCache =
        TestArtifactCaches.createDirCacheForTest(cacheDir, Paths.get("cache"));
    buildInfoRecorder.performUploadToArtifactCache(
        ImmutableSet.of(new RuleKey(RULE_KEY)),
        artifactCache,
        new DefaultBuckEventBus(new DefaultClock(), new BuildId()));
    assertTrue(
        cacheDir
            .resolve(
                DirArtifactCacheTestUtil.getPathForRuleKey(
                    artifactCache, new RuleKey(RULE_KEY), Optional.empty()))
            .toFile()
            .exists());
  }

  private static BuildInfoRecorder createBuildInfoRecorder(ProjectFilesystem filesystem) {
    return new BuildInfoRecorder(
        BUILD_TARGET,
        filesystem,
        new FilesystemBuildInfoStore(filesystem),
        new DefaultClock(),
        new BuildId(),
        ImmutableMap.of());
  }
}
