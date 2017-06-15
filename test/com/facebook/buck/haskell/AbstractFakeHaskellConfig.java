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

package com.facebook.buck.haskell;

import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractFakeHaskellConfig implements HaskellConfig {

  public static final FakeHaskellConfig DEFAULT = FakeHaskellConfig.builder().build();

  @Override
  @Value.Default
  public ToolProvider getCompiler() {
    return new ConstantToolProvider(new CommandTool.Builder().build());
  }

  @Override
  @Value.Default
  public HaskellVersion getHaskellVersion() {
    return HaskellVersion.of(7);
  }

  @Override
  public abstract ImmutableList<String> getCompilerFlags();

  @Override
  @Value.Default
  public ToolProvider getLinker() {
    return new ConstantToolProvider(new CommandTool.Builder().build());
  }

  @Override
  public abstract ImmutableList<String> getLinkerFlags();

  @Override
  @Value.Default
  public ToolProvider getPackager() {
    return new ConstantToolProvider(new CommandTool.Builder().build());
  }

  @Override
  @Value.Default
  public boolean shouldCacheLinks() {
    return true;
  }

  @Override
  public Path getGhciScriptTemplate() {
    throw new RuntimeException("Not supported for system compiler.");
  }

  @Override
  public Path getGhciBinutils() {
    throw new RuntimeException("Not supported for system compiler.");
  }

  @Override
  public Path getGhciGhc() {
    throw new RuntimeException("Not supported for system compiler.");
  }

  @Override
  public Path getGhciLib() {
    throw new RuntimeException("Not supported for system compiler.");
  }

  @Override
  public Path getGhciCxx() {
    throw new RuntimeException("Not supported for system compiler.");
  }

  @Override
  public Path getGhciCc() {
    throw new RuntimeException("Not supported for system compiler.");
  }

  @Override
  public Path getGhciCpp() {
    throw new RuntimeException("Not supported for system compiler.");
  }

  @Override
  public Optional<String> getPackageNamePrefix() {
    return Optional.empty();
  }
}
