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
import java.util.function.Consumer;

/**
 * An {@link Arg} implementation that delegates to another arg. Could be useful for overriding one
 * of the functions of the underlying Arg.
 */
public class ProxyArg implements Arg {
  @AddToRuleKey protected final Arg arg;

  public ProxyArg(Arg arg) {
    this.arg = arg;
  }

  @Override
  public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
    arg.appendToCommandLine(consumer, pathResolver);
  }

  @Override
  public String toString() {
    return arg.toString();
  }

  @Override
  public boolean equals(Object other) {
    return arg.equals(other) && getClass().equals(other.getClass());
  }

  @Override
  public int hashCode() {
    return arg.hashCode();
  }
}
