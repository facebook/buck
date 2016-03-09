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

package com.facebook.buck.rules;

/**
 * Denotes that this build rule is not cached.
 *
 * Uncached build rules are never written out to cache, never read from cache, and does not count in
 * cache statistics. This rule is useful for artifacts which cannot be easily normalized.
 *
 * Uncached rules are not always rebuilt, however, as long as the existing on-disk representation is
 * up to date. This means that these rules can take advantage of
 * {@link com.facebook.buck.rules.keys.SupportsInputBasedRuleKey} to prevent rebuilding.
 */
public interface UncachableBuildRule extends BuildRule {
}
