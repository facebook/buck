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

package com.facebook.buck.logd.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;

/** Only used for helping unit test. */
@VisibleForTesting
public interface TestHelper {
  /** Used for verify/inspect message received from server. */
  void onMessage(Message message);

  /** Used for verify/inspect error received from server. */
  void onRpcError(Throwable exception);
}
