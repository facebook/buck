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

import com.facebook.buck.util.types.Either;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

/** A small class that encapsulates a file hash that is either sha1, or sha256 */
public class FileHash {

  private final Either<HashCode, HashCode> sha1OrSha256;

  private FileHash(Either<HashCode, HashCode> sha1OrSha256) {
    this.sha1OrSha256 = sha1OrSha256;
  }

  /** Create a {@link FileHash} object with a sha1 hash */
  public static FileHash ofSha1(HashCode sha1) {
    return new FileHash(Either.ofLeft(sha1));
  }

  /** Create a {@link FileHash} object with a sha1 hash */
  public static FileHash ofSha256(HashCode sha256) {
    return new FileHash(Either.ofRight(sha256));
  }

  /** Get the hash function for this type of hash */
  public HashFunction getHashFunction() {
    if (sha1OrSha256.isLeft()) {
      return Hashing.sha1();
    } else {
      return Hashing.sha256();
    }
  }

  /** Get the original hash code object used to create this {@link FileHash} object */
  public HashCode getHashCode() {
    if (sha1OrSha256.isLeft()) {
      return sha1OrSha256.getLeft();
    } else {
      return sha1OrSha256.getRight();
    }
  }

  @Override
  public String toString() {
    return this.getHashCode().toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    FileHash fileHash = (FileHash) o;

    return sha1OrSha256 != null
        ? sha1OrSha256.equals(fileHash.sha1OrSha256)
        : fileHash.sha1OrSha256 == null;
  }

  @Override
  public int hashCode() {
    return sha1OrSha256 != null ? sha1OrSha256.hashCode() : 0;
  }
}
