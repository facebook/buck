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

package com.facebook.buck.testutil;

import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public class DummyFileHashCache implements FileHashCache {

  @Override
  public void invalidate(Path path) {}

  @Override
  public void invalidateAll() {}

  @Override
  public HashCode get(Path path) throws IOException {
    throw new NoSuchFileException(path.toString());
  }

  @Override
  public long getSize(Path path) throws IOException {
    throw new NoSuchFileException(path.toString());
  }

  @Override
  public HashCode getForArchiveMember(Path relativeArchivePath, Path memberPath)
      throws IOException {
    throw new NoSuchFileException(relativeArchivePath.toString());
  }

  @Override
  public void set(Path path, HashCode hashCode) {}

  @Override
  public FileHashCache.FileHashCacheVerificationResult verify() {
    return FileHashCache.FileHashCacheVerificationResult.of(1, 0);
  }
}
