/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.cache;

import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractHashCodeAndFileType {

  public abstract Type getType();

  public abstract HashCode getHashCode();

  public abstract ImmutableSet<Path> getChildren();

  @Value.Auxiliary
  abstract Optional<JarContentHasher> getJarContentHasher();

  @Value.Lazy
  ImmutableMap<Path, HashCodeAndFileType> getContents() {
    try {
      return getJarContentHasher().get().getContentHashes();
    } catch (IOException e) {
      throw new HumanReadableException(
          "Failed to load hashes from jar: " + getJarContentHasher().get().getJarRelativePath());
    }
  }

  @Value.Check
  void check() {
    Preconditions.checkState(getType() == Type.DIRECTORY || getChildren().isEmpty());
    Preconditions.checkState(getType() == Type.ARCHIVE || !getJarContentHasher().isPresent());
  }

  public static HashCodeAndFileType ofArchive(
      HashCode hashCode, JarContentHasher jarContentHasher) {
    return HashCodeAndFileType.builder()
        .setType(Type.ARCHIVE)
        .setGetHashCode(hashCode)
        .setJarContentHasher(jarContentHasher)
        .build();
  }

  public static HashCodeAndFileType ofDirectory(HashCode hashCode, ImmutableSet<Path> children) {
    return HashCodeAndFileType.builder()
        .setType(Type.DIRECTORY)
        .setGetHashCode(hashCode)
        .setChildren(children)
        .build();
  }

  public static HashCodeAndFileType ofFile(HashCode hashCode) {
    return HashCodeAndFileType.builder().setType(Type.FILE).setGetHashCode(hashCode).build();
  }

  public enum Type {
    ARCHIVE,
    FILE,
    DIRECTORY
  };
}
