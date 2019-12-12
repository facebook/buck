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

package com.facebook.buck.apple;

import com.google.common.collect.ImmutableList;
import java.util.Optional;

/** Defines a set of codesign-related fields that are applicable to bundle-like rules. */
public interface HasAppleCodesignFields {
  /**
   * @return Additional flags passed to the underlying codesign tool. For example, this can be used
   *     to sign deep macOS frameworks using "--deep".
   */
  ImmutableList<String> getCodesignFlags();

  /**
   * @return A codesign identity that will be used for adhoc signing (i.e., on platforms like macOS
   *     and simulators). This field can be used to sign with Developer ID on macOS.
   */
  Optional<String> getCodesignIdentity();
}
