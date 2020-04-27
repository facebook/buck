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

package com.facebook.buck.rules.param;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Comparator;

/** Rule parameter name or special attribute. */
public interface ParamNameOrSpecial {

  Comparator<ParamNameOrSpecial> COMPARATOR =
      (a, b) -> {
        boolean aIsSpecial = a instanceof SpecialAttr;
        boolean bIsSpecial = b instanceof SpecialAttr;
        if (aIsSpecial != bIsSpecial) {
          // special is less than regular
          // TODO: test
          return Boolean.compare(aIsSpecial, bIsSpecial);
        } else if (a instanceof SpecialAttr) {
          return ((SpecialAttr) a).compareTo((SpecialAttr) b);
        } else if (a instanceof ParamName) {
          return ((ParamName) a).compareTo((ParamName) b);
        } else {
          throw new IllegalStateException(
              "Type: " + a.getClass().getCanonicalName() + " is not supported");
        }
      };

  /** Legacy field name. */
  // TODO(nga): rename to getLegacyName
  String getCamelCase();

  /** Snake-case attribute. */
  @JsonValue
  String getSnakeCase();
}
