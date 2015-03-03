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

package com.facebook.buck.rules;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.timing.DefaultClock;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

public class BuildInfoRecorderIntegrationTest {
  private static final String RULE_KEY = Strings.repeat("a", 40);
  private static final String RULE_KEY_WITHOUT_DEPS = Strings.repeat("b", 40);

  private static final BuildTarget BUILD_TARGET = BuildTargetFactory.newInstance("//foo:bar");

  @Test
  public void testPerformUploadToArtifactCache() throws IOException, InterruptedException {
    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(
        new FakeProjectFilesystem() {
            @Override
            public void createZip(
                Collection<Path> pathsToIncludeInZip,
                File out,
                ImmutableMap<Path, String> additionalFileContents) throws IOException {
              // For this test, nothing really cares about the content, so just write out the name.
              writeBytesToPath(out.toString().getBytes(), out.toPath());
            }
        });
    DebuggableTemporaryFolder cacheDir = new DebuggableTemporaryFolder();
    cacheDir.create();
    ArtifactCache artifactCache = new DirArtifactCache(
        cacheDir.getRoot(),
        true,
        Optional.<Long>absent());
    buildInfoRecorder.performUploadToArtifactCache(
        artifactCache,
        new BuckEventBus(new DefaultClock(), new BuildId()));
    assertTrue(cacheDir.getRootPath().resolve(Paths.get(RULE_KEY)).toFile().exists());
  }

  private static BuildInfoRecorder createBuildInfoRecorder(ProjectFilesystem filesystem) {
    return new BuildInfoRecorder(
        BUILD_TARGET,
        filesystem,
        new DefaultClock(),
        new BuildId(),
        ImmutableMap.<String, String>of(),
        new RuleKey(RULE_KEY),
        new RuleKey(RULE_KEY_WITHOUT_DEPS));
  }
}
