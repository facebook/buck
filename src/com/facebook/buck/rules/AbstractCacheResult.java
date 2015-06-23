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

package com.facebook.buck.rules;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCacheResult {

  private static final CacheResult SKIP_RESULT =
      CacheResult.of(
          Type.SKIP,
          Optional.<String>absent(),
          Optional.<String>absent(),
          Optional.<ImmutableMap<String, String>>absent());
  private static final CacheResult MISS_RESULT =
      CacheResult.of(
          Type.MISS,
          Optional.<String>absent(),
          Optional.<String>absent(),
          Optional.<ImmutableMap<String, String>>absent());
  private static final CacheResult LOCAL_KEY_UNCHANGED_HIT_RESULT =
      CacheResult.of(
          Type.LOCAL_KEY_UNCHANGED_HIT,
          Optional.<String>absent(),
          Optional.<String>absent(),
          Optional.<ImmutableMap<String, String>>absent());

  @Value.Parameter
  @JsonProperty("type") public abstract Type getType();

  @Value.Parameter
  @JsonProperty("cacheSource") protected abstract Optional<String> cacheSource();

  @Value.Parameter
  @JsonProperty("cacheError") protected abstract Optional<String> cacheError();

  @Value.Parameter
  @JsonProperty("metadata") protected abstract Optional<ImmutableMap<String, String>> metadata();

  public String getCacheSource() {
    Preconditions.checkState(getType() == Type.HIT || getType() == Type.ERROR);
    return cacheSource().get();
  }

  public String getCacheError() {
    Preconditions.checkState(getType() == Type.ERROR);
    return cacheError().get();
  }

  public ImmutableMap<String, String> getMetadata() {
    Preconditions.checkState(getType() == Type.HIT);
    return metadata().get();
  }

  public String name() {
    String name = getType().name();
    if (cacheSource().isPresent()) {
      name = cacheSource().get().toUpperCase() + "_" + name;
    }
    return name;
  }

  public static CacheResult hit(String cacheSource, ImmutableMap<String, String> metadata) {
    return CacheResult.of(
        Type.HIT,
        Optional.of(cacheSource),
        Optional.<String>absent(),
        Optional.of(metadata));
  }

  public static CacheResult hit(String cacheSource) {
    return hit(cacheSource, ImmutableMap.<String, String>of());
  }

  public static CacheResult error(String cacheSource, String cacheError) {
    return CacheResult.of(
        Type.ERROR,
        Optional.of(cacheSource),
        Optional.of(cacheError),
        Optional.<ImmutableMap<String, String>>absent());
  }

  public static CacheResult miss() {
    return MISS_RESULT;
  }

  public static CacheResult skip() {
    return SKIP_RESULT;
  }

  public static CacheResult localKeyUnchangedHit() {
    return LOCAL_KEY_UNCHANGED_HIT_RESULT;
  }

  /**
   * @return a {@link CacheResult} constructed from trying to parse the given string representation.
   *     This is mainly available for backwards compatibility for when this class was an enum.
   */
  public static CacheResult valueOf(String val) {
    for (Type type : Type.values()) {
      if (val.endsWith(type.name())) {
        String rest = val.substring(0, val.length() - type.name().length());
        return CacheResult.of(
            type,
            rest.isEmpty() ?
                Optional.<String>absent() :
                Optional.of(rest.substring(0, rest.length() - 1).toLowerCase()),
            type == Type.ERROR ?
                Optional.of("") :
                Optional.<String>absent(),
            Optional.<ImmutableMap<String, String>>absent());
      }
    }
    throw new IllegalStateException("invalid cache result string: " + val);
  }

  @Override
  public String toString() {
    return name();
  }

  @Value.Check
  protected void check() {
    Preconditions.checkState(
        cacheSource().isPresent() || (getType() != Type.HIT && getType() != Type.ERROR));
    Preconditions.checkState(cacheError().isPresent() || getType() != Type.ERROR);
  }

  public enum Type {

    /** Artifact was successfully fetched from cache */
    HIT(/* success */ true),

    /** Artifact was missing from cache */
    MISS(/* success */ false),

    /** An error occurred when fetching artifact from cache*/
    ERROR(/* success */ false),

    /** Artifact cache not queried because some of the dependencies were re-built. */
    SKIP(/* success */ false),

    /** Artifact cache not queried because the local cache key was unchanged. */
    LOCAL_KEY_UNCHANGED_HIT(/* success */ true),

    ;

    private boolean success;

    Type(boolean success) {
      this.success = success;
    }

    public boolean isSuccess() {
      return success;
    }

  }

}
