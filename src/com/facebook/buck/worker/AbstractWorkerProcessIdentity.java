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

package com.facebook.buck.worker;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.hash.HashCode;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
interface AbstractWorkerProcessIdentity {

  /**
   * If the current invocation allows to have persisted worker pools (buck is running as daemon), it
   * will be used to obtain the instance of the persisted worker process pool.
   */
  String getPersistentWorkerKey();

  /**
   * Hash to identify the specific worker pool and kind of a mechanism for invalidating existing
   * pools. If this value for the given persistent worker key changes, old pool will be destroyed
   * and the new pool will be recreated.
   */
  HashCode getWorkerHash();
}
