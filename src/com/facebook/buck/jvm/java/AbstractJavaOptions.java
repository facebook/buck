/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableCollection;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractJavaOptions {
  public abstract ToolProvider getJavaRuntimeProvider();

  public Tool getJavaRuntimeLauncher(BuildRuleResolver ruleResolver) {
    return getJavaRuntimeProvider().resolve(ruleResolver);
  }

  public void addParseTimeDeps(ImmutableCollection.Builder<BuildTarget> depsBuilder) {
    depsBuilder.addAll(getJavaRuntimeProvider().getParseTimeDeps());
  }
}
