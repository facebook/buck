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

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.keys.RuleKeyType;
import java.util.Optional;

// CacheResult enriched with meta-data about the rule key that was used to make the cache request.
// Additionally it includes the request timestamp, and the two-level content hash (if one exists).
@BuckStyleValue
public abstract class RuleKeyCacheResult {
  public abstract String buildTarget();

  public abstract String ruleKey();

  public abstract long requestTimestampMillis();

  public abstract RuleKeyType ruleKeyType();

  public abstract CacheResultType cacheResult();

  public abstract Optional<String> twoLevelContentHashKey();

  public static RuleKeyCacheResult of(
      String buildTarget,
      String ruleKey,
      long requestTimestampMillis,
      RuleKeyType ruleKeyType,
      CacheResultType cacheResult,
      Optional<String> twoLevelContentHashKey) {
    return ImmutableRuleKeyCacheResult.of(
        buildTarget,
        ruleKey,
        requestTimestampMillis,
        ruleKeyType,
        cacheResult,
        twoLevelContentHashKey);
  }
}
