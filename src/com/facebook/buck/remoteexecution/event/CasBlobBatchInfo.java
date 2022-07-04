/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.remoteexecution.event;

/**
 * Class that's used to store info about a batch of blobs upload or downloaded from CAS. This class
 * is primarily used for logging
 */
public class CasBlobBatchInfo {
  // "Large" and "small" digests go to different backends (e.g. zippy and manifold),
  // so it makes sense to split them in two. See https://fburl.com/code/saw12ezj.
  private static final int LARGE_DIGEST_THRESHOLD = 2621440; // 2.5mb

  private final int smallBlobCount;
  private final long smallSizeBytes;
  private final int largeBlobCount;
  private final long largeSizeBytes;

  public CasBlobBatchInfo(long[] digestSizes) {
    int smallBlobCount = 0;
    long smallSizeBytes = 0;
    int largeBlobCount = 0;
    long largeSizeBytes = 0;

    for (long digestSize : digestSizes) {
      if (digestSize <= LARGE_DIGEST_THRESHOLD) {
        smallBlobCount += 1;
        smallSizeBytes += digestSize;
      } else {
        largeBlobCount += 1;
        largeSizeBytes += digestSize;
      }
    }

    this.smallBlobCount = smallBlobCount;
    this.smallSizeBytes = smallSizeBytes;
    this.largeBlobCount = largeBlobCount;
    this.largeSizeBytes = largeSizeBytes;
  }

  public int getSmallBlobCount() {
    return smallBlobCount;
  }

  public long getSmallBlobSize() {
    return smallSizeBytes;
  }

  public int getLargeBlobCount() {
    return largeBlobCount;
  }

  public long getLargeBlobSize() {
    return largeSizeBytes;
  }

  public int getBlobCount() {
    return getSmallBlobCount() + getLargeBlobCount();
  }

  public long getBlobSize() {
    return getSmallBlobSize() + getLargeBlobSize();
  }
}
