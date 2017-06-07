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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.google.common.collect.ImmutableSet;

/**
 * Each set of capture rules is headed by aggregating rules, e.g. analysis rules that run over these
 * captures, or collection rules that are needed only for separate post processing (e.g. transitive
 * capture of all source files)
 */
class CxxInferCaptureAndAggregatingRules<T extends BuildRule> {
  final ImmutableSet<CxxInferCapture> captureRules;
  final ImmutableSet<T> aggregatingRules;

  CxxInferCaptureAndAggregatingRules(
      ImmutableSet<CxxInferCapture> captureRules, ImmutableSet<T> aggregatingRules) {
    this.captureRules = captureRules;
    this.aggregatingRules = aggregatingRules;
  }
}
