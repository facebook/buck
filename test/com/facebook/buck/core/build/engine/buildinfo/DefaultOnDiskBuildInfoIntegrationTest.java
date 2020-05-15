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

package com.facebook.buck.core.build.engine.buildinfo;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.engine.buildinfo.BuildInfo.MetadataKey;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultOnDiskBuildInfoIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException exceptionRule = ExpectedException.none();

  public DefaultOnDiskBuildInfoIntegrationTest() {}

  private DefaultOnDiskInfoPreparer preparer;

  @Before
  public void setUp() throws IOException {
    preparer = new DefaultOnDiskInfoPreparer(tmp);
  }

  @Test
  public void testPathsAndMetadataForArtifactAreCorrect() throws IOException {
    assertEquals(
        ImmutableSortedSet.of(
            preparer.metadataDirectory,
            preparer.metadataDirectory.resolve(MetadataKey.ARTIFACT_METADATA),
            preparer.filePath.getPath(),
            preparer.dirPath.getPath(),
            preparer.subDir,
            preparer.emptySubDir,
            preparer.fileWithinDirPath,
            preparer.otherPathWithinDir,
            preparer.symlinkPath.getPath(),
            preparer.fileViaSymlinkPath),
        preparer.onDiskBuildInfo.getPathsForArtifact());
    assertEquals(
        ImmutableSortedMap.of(
            BuildInfo.MetadataKey.ADDITIONAL_INFO,
            "build_id=cat,timestamp=1,artifact_data=null,",
            MetadataKey.ORIGIN_BUILD_ID,
            "build-id",
            "build_key0",
            "value0",
            "build_key1",
            "value1"),
        ImmutableSortedMap.copyOf(preparer.onDiskBuildInfo.getMetadataForArtifact()));
  }

  @Test
  public void testSuccessfulValidation() throws IOException {
    preparer.onDiskBuildInfo.validateArtifact();
  }

  @Test
  public void testUnsuccessfulValidationDueToMissingRecordedPath() throws IOException {
    // Delete a path from RECORDED_PATH_HASHES which will be expected by the validation logic.
    // We're specifically not deleting a path from RECORDED_PATHS because that will throw
    // an exception much earlier when trying to compute output size, using a path from
    // RECORDED_PATH_HASHES means we're testing for additional failures.
    preparer.projectFilesystem.deleteFileAtPathIfExists(preparer.otherPathWithinDir);

    exceptionRule.expect(IllegalStateException.class);
    preparer.onDiskBuildInfo.validateArtifact();
  }
}
