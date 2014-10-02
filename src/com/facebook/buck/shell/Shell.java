/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.util.Escaper;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;

public class Shell {

  private Shell() {
  }

  /**
   * Quotes all strings using Escaper.escapeAsBashString and joins them using sep. Should be used
   * for constructing bash command lines, for example in bash scripts.
   *
   * @param items to join
   * @param sep   separator to use for joining
   * @return      joined quoted items as String
   */
  public static String shellQuoteJoin(Iterable<String> items, String sep) {
    return Joiner.on(sep).join(
        FluentIterable.from(items)
          .transform(new Function<String, String>() {
                       @Override
                       public String apply(String input) {
                         return Escaper.escapeAsBashString(input);
                       }
                     }));
  }

}
