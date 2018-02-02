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

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableList;
import java.util.function.Consumer;

/**
 * CompositeArg holds a list of args and appends them all to the command-line. It does not add any
 * separator between the args, so if that's necessary it should be added via StringArgs in the list
 * of Args.
 */
public class CompositeArg implements Arg {
  @AddToRuleKey private final ImmutableList<Arg> args;

  public CompositeArg(ImmutableList<Arg> args) {
    this.args = args;
  }

  @Override
  public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
    StringBuilder builder = new StringBuilder();
    args.forEach(arg -> arg.appendToCommandLine(builder::append, pathResolver));
    consumer.accept(builder.toString());
  }
}
