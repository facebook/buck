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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuckVersionType;
import com.facebook.buck.testutil.TemporaryPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class BuckVersionUtilTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testCreatingFromGit() {
    String gitHash = "My lovely random hash";
    BuckVersion buckVersion = BuckVersionUtil.createFromGitHash(gitHash);
    Assert.assertEquals(BuckVersionType.GIT, buckVersion.getType());
    Assert.assertEquals(gitHash, buckVersion.getGitHash());
  }

  @Test
  public void testCreatingDev() throws IOException {
    Path binFolder = temporaryFolder.newFolder("source");
    Path binary = binFolder.resolve("buck.binary");
    byte[] binaryData = "Sample binary data".getBytes();
    Files.write(binary, binaryData);

    BuckVersion buckVersion = BuckVersionUtil.createFromLocalBinary(binary);
    Assert.assertEquals(BuckVersionType.DEVELOPMENT, buckVersion.getType());
    Assert.assertArrayEquals(binaryData, buckVersion.getDevelopmentVersion().getContent());
    Assert.assertEquals(
        "8ffb80f59032d8e7647a5e4d0196cc7360a8eb2b",
        buckVersion.getDevelopmentVersion().getContentHash());
  }
}
