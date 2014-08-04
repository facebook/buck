/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

/**
 * Represents a single checkout of a code base. Two repositories model the same code base if their
 * underlying {@link ProjectFilesystem}s are equal.
 */
public class Repository {

  private final ProjectFilesystem filesystem;
  private final KnownBuildRuleTypes buildRuleTypes;
  private final BuckConfig buckConfig;

  // TODO(jacko): This is a hack to avoid breaking the build. Get rid of it.
  public final AndroidDirectoryResolver androidDirectoryResolver;

  @VisibleForTesting
  public Repository(
      ProjectFilesystem filesystem,
      KnownBuildRuleTypes buildRuleTypes,
      BuckConfig buckConfig,
      AndroidDirectoryResolver androidDirectoryResolver) {
    this.filesystem = Preconditions.checkNotNull(filesystem);
    this.buildRuleTypes = Preconditions.checkNotNull(buildRuleTypes);
    this.buckConfig = Preconditions.checkNotNull(buckConfig);
    this.androidDirectoryResolver = Preconditions.checkNotNull(androidDirectoryResolver);
  }

  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  public Description<? extends ConstructorArg> getDescription(BuildRuleType type) {
    return buildRuleTypes.getDescription(type);
  }

  public BuildRuleType getBuildRuleType(String rawType) {
    return buildRuleTypes.getBuildRuleType(rawType);
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return buildRuleTypes.getAllDescriptions();
  }

  public KnownBuildRuleTypes getKnownBuildRuleTypes() {
    return buildRuleTypes;
  }

  public BuckConfig getBuckConfig() {
    return buckConfig;
  }

  @Override
  public String toString() {
    return String.format("<Repository (%s)>", filesystem.getRootPath());
  }

  @Override
  public int hashCode() {
    return filesystem.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Repository)) {
      return false;
    }

    Repository that = (Repository) obj;
    return this.getFilesystem().equals(that.getFilesystem()) &&
        this.getBuckConfig().equals(that.getBuckConfig());
  }
}
