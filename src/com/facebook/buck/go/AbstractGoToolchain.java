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

package com.facebook.buck.go;

import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable(copy = false, builder = false)
@BuckStyleImmutable
public abstract class AbstractGoToolchain implements Toolchain {

  public static final String DEFAULT_NAME = "go-toolchain";

  @Value.Parameter
  protected abstract GoBuckConfig getGoBuckConfig();

  @Value.Parameter
  public abstract GoPlatformFlavorDomain getPlatformFlavorDomain();

  @Value.Parameter
  public abstract GoPlatform getDefaultPlatform();

  @Value.Parameter
  public abstract Path getGoRoot();

  @Value.Parameter
  public abstract Path getToolDir();

  ImmutableList<Path> getAssemblerIncludeDirs() {
    // TODO(mikekap): Allow customizing this via config.
    return ImmutableList.of(getGoRoot().resolve("pkg").resolve("include"));
  }

  public Tool getCompiler() {
    return getGoTool("compiler", "compile", "compiler_flags");
  }

  public Tool getAssembler() {
    return getGoTool("assembler", "asm", "asm_flags");
  }

  public Tool getCGo() {
    return getGoTool("cgo", "cgo", "");
  }

  public Tool getPacker() {
    return getGoTool("packer", "pack", "");
  }

  public Tool getLinker() {
    return getGoTool("linker", "link", "linker_flags");
  }

  public Tool getCover() {
    return getGoTool("cover", "cover", "");
  }

  private Tool getGoTool(
      final String configName, final String toolName, final String extraFlagsConfigKey) {
    Path toolPath = getGoBuckConfig().getPath(configName).orElse(getToolDir().resolve(toolName));

    CommandTool.Builder builder =
        new CommandTool.Builder(
            new HashedFileTool(() -> getGoBuckConfig().getDelegate().getPathSourcePath(toolPath)));
    if (!extraFlagsConfigKey.isEmpty()) {
      for (String arg : getFlags(extraFlagsConfigKey)) {
        builder.addArg(arg);
      }
    }
    builder.addEnv("GOROOT", getGoRoot().toString());
    return builder.build();
  }

  private ImmutableList<String> getFlags(String key) {
    return ImmutableList.copyOf(
        Splitter.on(" ").omitEmptyStrings().split(getGoBuckConfig().getValue(key).orElse("")));
  }
}
