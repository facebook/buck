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

package com.facebook.buck.rules.args;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.base.Joiner;
import java.util.function.Consumer;
import org.immutables.value.Value;

/** An Arg that just wraps a Tool. */
@Value.Immutable
@BuckStyleTuple
interface AbstractToolArg extends Arg {
  @AddToRuleKey
  Tool getTool();

  @Override
  default void appendToCommandLine(Consumer<String> consumer, SourcePathResolver resolver) {
    // TODO(mikekap): Pass environment variables through.
    consumer.accept(Joiner.on(' ').join(getTool().getCommandPrefix(resolver)));
  }
}
