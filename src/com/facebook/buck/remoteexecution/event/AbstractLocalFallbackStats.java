/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.event;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/** Statistics regarding the LocalFallbackStrategy. */
@Value.Immutable
@BuckStyleImmutable
interface AbstractLocalFallbackStats {

  /** The total number of actions executed both remote and local. */
  int getTotalExecutedRules();

  /** Get the number of actions that failed remotely and fallback'ed locally. */
  int getLocallyExecutedRules();

  /** For all the actions that fallback'ed to run locally, the ones that finished successful. */
  int getLocallySuccessfulRules();
}
