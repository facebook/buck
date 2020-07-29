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

package com.facebook.buck.apple;

import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import java.io.IOException;

/** Utility methods to support hashes computation for files and directories */
public class AppleComputeHashSupport {

  public static Sha1HashCode computeHash(String data) throws IOException {
    ByteSource byteSource = ByteSource.wrap(data.getBytes(Charsets.UTF_8));
    return Sha1HashCode.fromHashCode(byteSource.hash(Hashing.sha1()));
  }
}
