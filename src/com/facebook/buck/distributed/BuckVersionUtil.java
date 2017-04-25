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
import com.facebook.buck.distributed.thrift.FileInfo;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public abstract class BuckVersionUtil {
  private BuckVersionUtil() {
    // do not instantiate
  }

  public static BuckVersion createFromGitHash(String gitHash) {
    BuckVersion buckVersion = new BuckVersion();
    buckVersion.setType(BuckVersionType.GIT);
    buckVersion.setGitHash(gitHash);
    return buckVersion;
  }

  public static BuckVersion createFromLocalBinary(Path localBuckBinary) throws IOException {
    byte[] data = Files.readAllBytes(localBuckBinary);
    String hash = Hashing.sha1().newHasher().putBytes(data).hash().toString();

    FileInfo buckBinary = new FileInfo();
    buckBinary.setContent(data);
    buckBinary.setContentHash(hash);

    BuckVersion buckVersion = new BuckVersion();
    buckVersion.setType(BuckVersionType.DEVELOPMENT);
    buckVersion.setDevelopmentVersion(buckBinary);
    return buckVersion;
  }
}
