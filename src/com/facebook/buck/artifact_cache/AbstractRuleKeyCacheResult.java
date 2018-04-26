/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.keys.RuleKeyType;
import java.util.Optional;
import org.immutables.value.Value;

// CacheResult enriched with meta-data about the rule key that was used to make the cache request.
// Additionally it includes the request timestamp, and the two-level content hash (if one exists).
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractRuleKeyCacheResult {
  abstract String buildTarget();

  abstract String ruleKey();

  abstract long requestTimestampMillis();

  abstract RuleKeyType ruleKeyType();

  abstract CacheResultType cacheResult();

  abstract Optional<String> twoLevelContentHashKey();
}
