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

package com.facebook.buck.core.build.engine.cache.manager;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.buildinfo.DefaultOnDiskInfoPreparer;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.testutil.TemporaryPaths;
import java.io.IOException;
import java.util.Optional;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildCacheArtifactUploaderTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException exceptionRule = ExpectedException.none();

  private DefaultOnDiskInfoPreparer preparer;

  @Before
  public void setUp() throws IOException {
    preparer = new DefaultOnDiskInfoPreparer(tmp);
  }

  @Test
  public void testValidationSuccessful() throws IOException {
    BuildCacheArtifactUploader uploader = makeBuildCacheArtifactUploader();
    // Will check that metadata in sync with contents to be uploaded.
    uploader.uploadToCache(BuildRuleSuccessType.BUILT_LOCALLY, 1000);
  }

  @Test
  public void testValidationUnsuccessful() throws IOException {
    BuildCacheArtifactUploader uploader = makeBuildCacheArtifactUploader();
    // Deletes a hashed file that's _inside_ a recorded path. That means that at the time of upload,
    // there will be a discrepancy between metadata and files to be uploaded.
    preparer.projectFilesystem.deleteFileAtPathIfExists(preparer.otherPathWithinDir);
    exceptionRule.expect(IllegalStateException.class);
    uploader.uploadToCache(BuildRuleSuccessType.BUILT_LOCALLY, 1000);
  }

  private BuildCacheArtifactUploader makeBuildCacheArtifactUploader() {
    RuleKey fakeRuleKey = new RuleKey("aaaa");
    BuildRule fakeRule = new FakeBuildRule("//fake:rule1");
    ManifestRuleKeyManager fakeRuleKeyManager = EasyMock.niceMock(ManifestRuleKeyManager.class);
    BuckEventBus fakeEventBus = EasyMock.niceMock(BuckEventBus.class);
    ArtifactCache fakeArtifactCache = EasyMock.niceMock(ArtifactCache.class);
    BuildCacheArtifactUploader.NetworkUploader networkUploader =
        EasyMock.niceMock(BuildCacheArtifactUploader.NetworkUploader.class);

    return new BuildCacheArtifactUploader(
        fakeRuleKey,
        () -> Optional.empty(),
        preparer.onDiskBuildInfo,
        fakeRule,
        fakeRuleKeyManager,
        fakeEventBus,
        fakeArtifactCache,
        Optional.empty(),
        networkUploader);
  }
}
