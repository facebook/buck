/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.features.rust;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.impl.DefaultLinkerProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** An {@link UnresolvedRustPlatform} based on .buckconfig values. */
public class ConfigBasedUnresolvedRustPlatform implements UnresolvedRustPlatform {
  private static final Path DEFAULT_RUSTC_COMPILER = Paths.get("rustc");

  private final RustBuckConfig rustBuckConfig;
  private final String name;
  private final ToolProvider rustCompiler;
  private final Optional<ToolProvider> linkerOverride;
  private final UnresolvedCxxPlatform unresolvedCxxPlatform;

  ConfigBasedUnresolvedRustPlatform(
      String name,
      BuckConfig buckConfig,
      ExecutableFinder executableFinder,
      UnresolvedCxxPlatform unresolvedCxxPlatform) {
    this.rustBuckConfig = new RustBuckConfig(buckConfig);
    this.name = name;
    this.unresolvedCxxPlatform = unresolvedCxxPlatform;
    this.rustCompiler =
        rustBuckConfig
            .getRustCompiler(name)
            .orElseGet(
                () -> {
                  HashedFileTool tool =
                      new HashedFileTool(
                          () ->
                              buckConfig.getPathSourcePath(
                                  executableFinder.getExecutable(
                                      DEFAULT_RUSTC_COMPILER, buckConfig.getEnvironment())));
                  return new ConstantToolProvider(tool);
                });

    this.linkerOverride = rustBuckConfig.getRustLinker(name);
  }

  @Override
  public RustPlatform resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    CxxPlatform cxxPlatform = unresolvedCxxPlatform.resolve(resolver, targetConfiguration);
    LinkerProvider linkerProvider =
        linkerOverride
            .map(
                tp ->
                    (LinkerProvider)
                        new DefaultLinkerProvider(
                            rustBuckConfig
                                .getLinkerPlatform(name)
                                .orElse(cxxPlatform.getLd().getType()),
                            tp,
                            true))
            .orElseGet(cxxPlatform::getLd);
    return RustPlatform.builder()
        .setRustCompiler(rustCompiler)
        .addAllRustLibraryFlags(rustBuckConfig.getRustcLibraryFlags(name))
        .addAllRustBinaryFlags(rustBuckConfig.getRustcBinaryFlags(name))
        .addAllRustTestFlags(rustBuckConfig.getRustcTestFlags(name))
        .addAllRustCheckFlags(rustBuckConfig.getRustcCheckFlags(name))
        .setLinker(linkerOverride)
        .setLinkerProvider(linkerProvider)
        .addAllLinkerArgs(rustBuckConfig.getLinkerFlags(name))
        .addAllLinkerArgs(
            !linkerOverride.isPresent() ? cxxPlatform.getLdflags() : ImmutableList.of())
        .setCxxPlatform(cxxPlatform)
        .build();
  }

  @Override
  public Flavor getFlavor() {
    return unresolvedCxxPlatform.getFlavor();
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    Builder<BuildTarget> deps =
        ImmutableList.<BuildTarget>builder()
            .addAll(unresolvedCxxPlatform.getParseTimeDeps(targetConfiguration));
    deps.addAll(rustCompiler.getParseTimeDeps(targetConfiguration));
    linkerOverride.ifPresent(l -> deps.addAll(l.getParseTimeDeps(targetConfiguration)));
    return deps.build();
  }
}
