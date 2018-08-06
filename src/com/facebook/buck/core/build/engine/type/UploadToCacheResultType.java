/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine.type;

/** This defines if an a target is cacheable or the reason why it is not. */
public enum UploadToCacheResultType {

  // The rule is cacheable.
  CACHEABLE,

  // The rule is un-cacheable.
  UNCACHEABLE,

  // The specific rule has been marked as un-cacheable.
  UNCACHEABLE_RULE,

  // The rule could have been cached but was over the allowed output_size for the cache.
  CACHEABLE_OVER_SIZE_LIMIT,

  // The rule could have been cached but the cache was marked read-only.
  CACHEABLE_READONLY_CACHE,
}
