/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java.classes;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import java.io.IOException;

public abstract class AbstractFileLike implements FileLike {
  @Override
  public HashCode fastHash() throws IOException {
    // Default non-fast implementation.
    return ByteStreams.hash(new FileLikeInputSupplier(this), Hashing.sha1());
  }

  @Override
  public String toString() {
    return getRelativePath() + " (in " + getContainer() + ")";
  }
}
