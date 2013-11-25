/*
 * Copyright 2013-present Facebook, Inc.
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
 * Only apply to those BuildRules that need to store additional information to disk when run.
 */
public interface InitializableFromDisk {
  /**
   * For a rule that is read from the build cache, it may have fields that would normally be
   * populated by executing the steps returned by
   * {@link Buildable#getBuildSteps(BuildContext, BuildableContext)}. Because
   * {@link Buildable#getBuildSteps(BuildContext, BuildableContext)} is not invoked for cached
   * rules, a rule may need to implement this method to populate those fields in some other way. For
   * a cached rule, this method will be invoked just before the future returned by
   * {@link BuildRule#build(BuildContext)} is resolved.
   *
   * @param onDiskBuildInfo can be used to read metadata from disk to help initialize the rule.
   */
  void initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo);
}
