/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.file;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import org.junit.Assert;
import org.junit.Test;

public class FileHashTest {

  @Test
  public void returnsCorrectHashCodeAndHashFunctionForSha1() {
    FileHash hash =
        FileHash.ofSha1(HashCode.fromString("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"));
    Assert.assertEquals(Hashing.sha1(), hash.getHashFunction());
    Assert.assertEquals(
        HashCode.fromString("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"), hash.getHashCode());
  }

  @Test
  public void returnsCorrectHashCodeAndHashFunctionForSha256() {
    FileHash hash =
        FileHash.ofSha256(
            HashCode.fromString(
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"));
    Assert.assertEquals(Hashing.sha256(), hash.getHashFunction());
    Assert.assertEquals(
        HashCode.fromString("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"),
        hash.getHashCode());
  }
}
