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

package com.facebook.buck.core.build.event;

/**
 * Reasons why Buck would resume a rule, to be used by event consumers to infer what Buck was
 * intending to do.
 */
public enum BuildRuleResumeReason {
  /**
   * We haven't classified this usage of `Resume` yet, so it's effectively unknown what Buck was
   * going to do.
   */
  UNKNOWN,
}
