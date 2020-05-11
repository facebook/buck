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

package com.facebook.buck.core.select;

import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.google.common.base.Preconditions;

/** A pair of section name and property for buckconfig. */
@BuckStylePrehashedValue
public abstract class BuckConfigKey {
  public abstract String getSection();

  public abstract String getProperty();

  public static BuckConfigKey of(String section, String property) {
    return ImmutableBuckConfigKey.ofImpl(section, property);
  }

  /** Split buckconfig by dot. */
  public static BuckConfigKey parse(String key) {
    String[] keyParts = key.split("\\.");
    Preconditions.checkArgument(
        keyParts.length == 2,
        String.format("Config option should be in format 'section.option', but given: %s", key));
    return of(keyParts[0], keyParts[1]);
  }
}
