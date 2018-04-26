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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractCacheResult {

  private static final CacheResult MISS_RESULT =
      CacheResult.of(
          CacheResultType.MISS,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
  private static final CacheResult IGNORED_RESULT =
      CacheResult.of(
          CacheResultType.IGNORED,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
  private static final CacheResult LOCAL_KEY_UNCHANGED_HIT_RESULT =
      CacheResult.of(
          CacheResultType.LOCAL_KEY_UNCHANGED_HIT,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
  public static final CacheResult SKIPPED_RESULT =
      CacheResult.of(
          CacheResultType.SKIPPED,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.of(ImmutableMap.of()),
          Optional.empty(),
          Optional.empty());

  @Value.Parameter
  @JsonView(JsonViews.MachineReadableLog.class)
  @JsonProperty("type")
  public abstract CacheResultType getType();

  @Value.Parameter
  @JsonView(JsonViews.MachineReadableLog.class)
  @JsonProperty("cacheSource")
  protected abstract Optional<String> cacheSource();

  @Value.Parameter
  @JsonView(JsonViews.MachineReadableLog.class)
  @JsonProperty("cacheMode")
  protected abstract Optional<ArtifactCacheMode> cacheMode();

  @Value.Parameter
  @JsonProperty("cacheError")
  protected abstract Optional<String> cacheError();

  @Value.Parameter
  @JsonProperty("metadata")
  protected abstract Optional<ImmutableMap<String, String>> metadata();

  @Value.Parameter
  @JsonProperty("artifactSizeBytes")
  protected abstract Optional<Long> artifactSizeBytes();

  @Value.Parameter
  @JsonProperty("twoLevelContentHashKey")
  protected abstract Optional<String> twoLevelContentHashKey();

  public String getCacheSource() {
    Preconditions.checkState(
        getType() == CacheResultType.HIT
            || getType() == CacheResultType.ERROR
            || getType() == CacheResultType.CONTAINS);
    return cacheSource().get();
  }

  public String getCacheError() {
    Preconditions.checkState(getType() == CacheResultType.ERROR);
    return cacheError().get();
  }

  public ImmutableMap<String, String> getMetadata() {
    Preconditions.checkState(getType() == CacheResultType.HIT);
    return metadata().get();
  }

  public long getArtifactSizeBytes() {
    Preconditions.checkState(getType() == CacheResultType.HIT);
    return artifactSizeBytes().get();
  }

  public String name() {
    String name = getType().name();
    if (cacheSource().isPresent()) {
      name = cacheSource().get().toUpperCase() + "_" + name;
    }
    return name;
  }

  public static CacheResult hit(
      String cacheSource,
      ArtifactCacheMode cacheMode,
      ImmutableMap<String, String> metadata,
      long artifactSize) {
    return CacheResult.of(
        CacheResultType.HIT,
        Optional.of(cacheSource),
        Optional.of(cacheMode),
        Optional.empty(),
        Optional.of(metadata),
        Optional.of(artifactSize),
        Optional.empty());
  }

  public static CacheResult hit(String cacheSource, ArtifactCacheMode cacheMode) {
    return CacheResult.of(
        CacheResultType.HIT,
        Optional.of(cacheSource),
        Optional.of(cacheMode),
        Optional.empty(),
        Optional.of(ImmutableMap.of()),
        Optional.empty(),
        Optional.empty());
  }

  public static CacheResult error(
      String cacheSource, ArtifactCacheMode cacheMode, String cacheError) {
    return CacheResult.of(
        CacheResultType.ERROR,
        Optional.of(cacheSource),
        Optional.of(cacheMode),
        Optional.of(cacheError),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public static CacheResult skipped() {
    return SKIPPED_RESULT;
  }

  public static CacheResult miss() {
    return MISS_RESULT;
  }

  public static CacheResult ignored() {
    return IGNORED_RESULT;
  }

  public static CacheResult contains(String cacheSource, ArtifactCacheMode cacheMode) {
    return CacheResult.of(
        CacheResultType.CONTAINS,
        Optional.of(cacheSource),
        Optional.of(cacheMode),
        Optional.empty(),
        Optional.of(ImmutableMap.of()),
        Optional.empty(),
        Optional.empty());
  }

  public static CacheResult localKeyUnchangedHit() {
    return LOCAL_KEY_UNCHANGED_HIT_RESULT;
  }

  /**
   * @return a {@link CacheResult} constructed from trying to parse the given string representation.
   *     This is mainly available for backwards compatibility for when this class was an enum.
   */
  public static CacheResult valueOf(String val) {
    for (CacheResultType type : CacheResultType.values()) {
      if (val.endsWith(type.name())) {
        String rest = val.substring(0, val.length() - type.name().length());
        return CacheResult.of(
            type,
            rest.isEmpty()
                ? Optional.empty()
                : Optional.of(rest.substring(0, rest.length() - 1).toLowerCase()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
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
        cacheSource().isPresent()
            || (getType() != CacheResultType.HIT
                && getType() != CacheResultType.ERROR
                && getType() != CacheResultType.CONTAINS));
    Preconditions.checkState(cacheError().isPresent() || getType() != CacheResultType.ERROR);
  }
}
