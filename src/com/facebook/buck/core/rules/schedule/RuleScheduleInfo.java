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

package com.facebook.buck.core.rules.schedule;

import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Used to override how the build engine schedules a build rule. */
@BuckStyleValue
public abstract class RuleScheduleInfo {

  public static final RuleScheduleInfo DEFAULT = of();

  /**
   * @return the multiplier used to calculate the number of jobs to assign to a rule when running
   *     its steps.
   */
  public abstract int getJobsMultiplier();

  public static RuleScheduleInfo of() {
    return RuleScheduleInfo.of(1);
  }

  public static RuleScheduleInfo of(int jobsMultiplier) {
    return ImmutableRuleScheduleInfo.of(jobsMultiplier);
  }
}
