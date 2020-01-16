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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class ArtifactInfo {
  public abstract ImmutableSet<RuleKey> getRuleKeys();

  public abstract ImmutableMap<String, String> getMetadata();

  public abstract Optional<BuildTarget> getBuildTarget();

  public abstract Optional<String> getRepository();

  @Value.Default
  public long getBuildTimeMs() {
    return -1;
  }

  @Value.Default
  public boolean isManifest() {
    return false;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableArtifactInfo.Builder {}
}
