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

package com.facebook.buck.core.starlark.compatible;

import com.google.common.collect.ImmutableMap;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/** Simple try-with-resources class that creates and cleans up a mutable environment */
public class TestMutableEnv implements AutoCloseable {
  private final Mutability mutability;
  private final Module module;
  private final StarlarkThread env;

  public TestMutableEnv() {
    this(ImmutableMap.of());
  }

  public TestMutableEnv(ImmutableMap<String, Object> globals) {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    builder.putAll(Starlark.UNIVERSE);
    builder.putAll(globals);
    builder.putAll(Starlark.UNIVERSE);
    module = Module.withPredeclared(BuckStarlark.BUCK_STARLARK_SEMANTICS, globals);
    mutability = Mutability.create("testing");
    env = new StarlarkThread(mutability, BuckStarlark.BUCK_STARLARK_SEMANTICS);
  }

  public Module getModule() {
    return module;
  }

  public StarlarkThread getEnv() {
    return env;
  }

  @Override
  public void close() {
    mutability.close();
  }
}
