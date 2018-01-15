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

package com.facebook.buck.js;

import com.facebook.buck.rules.macros.StringWithMacros;
import java.util.Optional;

/** Common interface for rule args that have used-defined JSON that is passed on to the worker. */
public interface HasExtraJson {
  /**
   * A JSON string, optionally containing macros. Macros can be escaped for embedding into strings.
   */
  Optional<StringWithMacros> getExtraJson();
}
