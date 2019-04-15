/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class WorkerToolBuilder
    extends AbstractNodeBuilder<
        WorkerToolDescriptionArg.Builder,
        WorkerToolDescriptionArg,
        WorkerToolDescription,
        DefaultWorkerToolRule> {
  private WorkerToolBuilder(BuildTarget target) {
    super(new WorkerToolDescription(FakeBuckConfig.builder().build()), target);
  }

  public static WorkerToolBuilder newWorkerToolBuilder(BuildTarget target) {
    return new WorkerToolBuilder(target);
  }

  public WorkerToolBuilder setEnv(ImmutableMap<String, StringWithMacros> env) {
    getArgForPopulating().setEnv(env);
    return this;
  }

  public WorkerToolBuilder setExe(BuildTarget exe) {
    getArgForPopulating().setExe(exe);
    return this;
  }

  public WorkerToolBuilder setArgs(StringWithMacros... args) {
    getArgForPopulating().setArgs(Either.ofRight(ImmutableList.copyOf(args)));
    return this;
  }

  public WorkerToolBuilder setMaxWorkers(int maxWorkers) {
    getArgForPopulating().setMaxWorkers(maxWorkers);
    return this;
  }
}
