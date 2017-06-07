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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/** Used to override how the build engine schedules a build rule. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractRuleScheduleInfo {

  public static final RuleScheduleInfo DEFAULT = RuleScheduleInfo.builder().build();

  /**
   * @return the multiplier used to calculate the number of jobs to assign to a rule when running
   *     its steps.
   */
  @Value.Default
  public int getJobsMultiplier() {
    return 1;
  }
}
