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

package com.facebook.buck.rules.args;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.function.Consumer;

/**
 * Arg that stringifies another {@link Arg}, and passes that string representation into {@link
 * java.lang.String#format(java.lang.String, java.lang.Object...)}
 */
@BuckStyleValue
public abstract class FormatArg implements Arg {

  @AddToRuleKey
  public abstract Arg getArg();

  /**
   * @return the format string
   *     <p>The format string should be either a string with one or more %s. Each %s will be
   *     replaced with the string value of {@link #getArg()}
   */
  @AddToRuleKey
  public abstract String getFormatString();

  @Override
  public void appendToCommandLine(
      Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
    StringBuilder builder = new StringBuilder();
    getArg().appendToCommandLine(builder::append, pathResolver);
    String stringified = builder.toString();
    String formatString = getFormatString();
    if (formatString.isEmpty() || formatString.equals("%s")) {
      consumer.accept(stringified);
    } else {
      consumer.accept(getFormatString().replace("%s", stringified));
    }
  }
}
