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

package com.facebook.buck.jvm.java;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractJarParameters {
  @Value.Default
  public boolean getHashEntries() {
    return false;
  }

  @Value.Default
  public boolean getMergeManifests() {
    return false;
  }

  @Value.Default
  public boolean getDisallowAllDuplicates() {
    return false;
  }

  public abstract Path getJarPath();

  @Value.Default
  public Predicate<Object> getRemoveEntryPredicate() {
    return RemoveClassesPatternsMatcher.EMPTY;
  }

  public abstract ImmutableSortedSet<Path> getEntriesToJar();

  public abstract Optional<String> getMainClass();

  public abstract Optional<Path> getManifestFile();

  @Value.Default
  public Level getDuplicatesLogLevel() {
    return Level.INFO;
  }
}
