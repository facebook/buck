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

package com.facebook.buck.rules;

import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableSet;
import java.util.EnumSet;

/**
 * Token provided by the result of {@link BuildEngine#build(BuildEngineBuildContext,
 * com.facebook.buck.step.ExecutionContext, BuildRule)}, demonstrating that the associated {@link
 * BuildRule} was built successfully.
 */
public enum BuildRuleSuccessType {

  /** Built by executing the {@link Step}s for the rule. */
  BUILT_LOCALLY(
      "BUILT",
      Property.SHOULD_UPLOAD_RESULTING_ARTIFACT,
      Property.SHOULD_CLEAR_AND_WRITE_METADATA_ON_DISK,
      Property.OUTPUTS_HAVE_CHANGED),

  /** Fetched via the {@link com.facebook.buck.artifact_cache.ArtifactCache}. */
  FETCHED_FROM_CACHE("CACHE", Property.OUTPUTS_HAVE_CHANGED),

  /**
   * Fetched via the {@link com.facebook.buck.artifact_cache.ArtifactCache} using an input-based
   * rule key.
   */
  FETCHED_FROM_CACHE_INPUT_BASED(
      "CACHE",
      Property.SHOULD_UPLOAD_RESULTING_ARTIFACT,
      Property.SHOULD_UPDATE_METADATA_ON_DISK,
      Property.OUTPUTS_HAVE_CHANGED),

  /**
   * Fetched via the {@link com.facebook.buck.artifact_cache.ArtifactCache} using an input-based
   * rule key.
   */
  FETCHED_FROM_CACHE_MANIFEST_BASED(
      "CACHE",
      Property.SHOULD_UPLOAD_RESULTING_ARTIFACT,
      Property.SHOULD_UPDATE_METADATA_ON_DISK,
      Property.OUTPUTS_HAVE_CHANGED),

  /** Computed {@link RuleKey} matches the one on disk. */
  MATCHING_RULE_KEY("FOUND", Property.MATCHING_KEY),

  /** Computed input-based {@link RuleKey} matches the one on disk. */
  // TODO(#8364892): We should re-upload to the cache under the main rule key once local
  // caching performance is better and we don't hurt the incremental workflow as much.
  MATCHING_INPUT_BASED_RULE_KEY("FOUND", Property.MATCHING_KEY),

  /** Computed dep-file {@link RuleKey} matches the one on disk */
  MATCHING_DEP_FILE_RULE_KEY("FOUND", Property.MATCHING_KEY),
  ;

  private final String shortDescription;
  private final EnumSet<Property> properties;

  BuildRuleSuccessType(String shortDescription, Property... properties) {
    this.shortDescription = shortDescription;
    this.properties = EnumSet.copyOf(ImmutableSet.copyOf(properties));
  }

  public boolean shouldWriteRecordedMetadataToDiskAfterBuilding() {
    return properties.contains(Property.SHOULD_UPDATE_METADATA_ON_DISK)
        || properties.contains(Property.SHOULD_CLEAR_AND_WRITE_METADATA_ON_DISK);
  }

  public boolean shouldClearAndOverwriteMetadataOnDisk() {
    return properties.contains(Property.SHOULD_CLEAR_AND_WRITE_METADATA_ON_DISK);
  }

  public boolean shouldUploadResultingArtifact() {
    return properties.contains(Property.SHOULD_UPLOAD_RESULTING_ARTIFACT);
  }

  public String getShortDescription() {
    return shortDescription;
  }

  private enum Property {
    SHOULD_UPLOAD_RESULTING_ARTIFACT,
    SHOULD_CLEAR_AND_WRITE_METADATA_ON_DISK,
    SHOULD_UPDATE_METADATA_ON_DISK,
    OUTPUTS_HAVE_CHANGED,
    MATCHING_KEY,
  }
}
