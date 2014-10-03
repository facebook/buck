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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.util.EnumSet;

/**
 * Token provided by the result of {@link BuildEngine#build(BuildContext, BuildRule)},
 * demonstrating that the associated {@link BuildRule} was built successfully.
 */
public class BuildRuleSuccess {

  private final BuildRule rule;
  private final Type type;

  private static enum Property {
    SHOULD_UPLOAD_RESULTING_ARTIFACT,
    SHOULD_CLEAR_AND_WRITE_METADATA_ON_DISK,
    SHOULD_UPDATE_METADATA_ON_DISK,
  }

  public static enum Type {
    /** Built by executing the {@link Step}s for the rule. */
    BUILT_LOCALLY(
        Property.SHOULD_UPLOAD_RESULTING_ARTIFACT,
        Property.SHOULD_CLEAR_AND_WRITE_METADATA_ON_DISK
        ),

    /** Fetched via the {@link ArtifactCache}. */
    FETCHED_FROM_CACHE(
        ),

    /** Computed {@link RuleKey} matches the one on disk. */
    MATCHING_RULE_KEY(
        ),

    /**
     * Computed {@link RuleKey} without deps matches the one on disk <em>AND</em> the ABI key for
     * the deps matches the one on disk.
     */
    MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS(
        Property.SHOULD_UPDATE_METADATA_ON_DISK
        ),

    ;

    private final EnumSet<Property> properties;

    private Type() {
      this.properties = EnumSet.noneOf(Property.class);
    }

    private Type(Property... properties) {
      this.properties = EnumSet.copyOf(ImmutableSet.copyOf(properties));
    }

    public boolean shouldWriteRecordedMetadataToDiskAfterBuilding() {
      return properties.contains(Property.SHOULD_UPDATE_METADATA_ON_DISK) ||
          properties.contains(Property.SHOULD_CLEAR_AND_WRITE_METADATA_ON_DISK);

    }

    public boolean shouldClearAndOverwriteMetadataOnDisk() {
      return properties.contains(Property.SHOULD_CLEAR_AND_WRITE_METADATA_ON_DISK);
    }

    public boolean shouldUploadResultingArtifact() {
      return properties.contains(Property.SHOULD_UPLOAD_RESULTING_ARTIFACT);
    }
  }

  public BuildRuleSuccess(BuildRule rule, Type type) {
    this.rule = Preconditions.checkNotNull(rule);
    this.type = type;
  }

  public Type getType() {
    return type;
  }

  public BuildRule getRule() {
    return rule;
  }

  @Override
  public String toString() {
    return rule.getFullyQualifiedName();
  }
}
