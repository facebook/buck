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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.impl.DefaultLinkerProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Option;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/** An {@link UnresolvedRustPlatform} based on .buckconfig values. */
public class ConfigBasedUnresolvedRustPlatform implements UnresolvedRustPlatform {
  private static final Path DEFAULT_RUSTC_COMPILER = Paths.get("rustc");

  private final RustBuckConfig rustBuckConfig;
  private final String name;
  private final ToolProvider rustCompiler;
  private final Optional<ToolProvider> linkerOverride;
  private final UnresolvedCxxPlatform unresolvedCxxPlatform;
  private final @Nullable ProcessExecutor processExecutor;

  private static final Logger LOG = Logger.get(RustBuckConfig.class);

  ConfigBasedUnresolvedRustPlatform(
      String name,
      BuckConfig buckConfig,
      ExecutableFinder executableFinder,
      UnresolvedCxxPlatform unresolvedCxxPlatform,
      @Nullable ProcessExecutor processExecutor) {
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
    this.processExecutor = processExecutor;
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
    ImmutableRustPlatform.Builder builder =
        ImmutableRustPlatform.builder()
            .setRustCompiler(rustCompiler)
            .addAllRustLibraryFlags(
                rustBuckConfig.getRustcLibraryFlags(name).stream()
                    .map(StringArg::of)
                    .collect(ImmutableList.toImmutableList()))
            .addAllRustBinaryFlags(
                rustBuckConfig.getRustcBinaryFlags(name).stream()
                    .map(StringArg::of)
                    .collect(ImmutableList.toImmutableList()))
            .addAllRustTestFlags(
                rustBuckConfig.getRustcTestFlags(name).stream()
                    .map(StringArg::of)
                    .collect(ImmutableList.toImmutableList()))
            .addAllRustCheckFlags(
                rustBuckConfig.getRustcCheckFlags(name).stream()
                    .map(StringArg::of)
                    .collect(ImmutableList.toImmutableList()))
            .setLinker(linkerOverride)
            .setLinkerProvider(linkerProvider)
            .addAllLinkerArgs(
                rustBuckConfig.getLinkerFlags(name).stream()
                    .map(StringArg::of)
                    .collect(ImmutableList.toImmutableList()))
            .setCxxPlatform(cxxPlatform)
            .setXcrunSdkPath(computeXcrunSdkPath(cxxPlatform.getFlavor()));

    if (!linkerOverride.isPresent()) {
      builder.addAllLinkerArgs(cxxPlatform.getLdflags());
    }

    return builder.build();
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

  private Optional<Path> computeXcrunSdkPath(Flavor flavor) {
    Optional<String> xcrunSdk = getXcrunSdk(flavor);
    if (processExecutor == null) {
      if (xcrunSdk.isPresent()) {
        LOG.warn(
            "No processExecutor while trying to get Apple SDK path for rustc. This is unlikely to work.");
      }
      return Optional.empty();
    }
    return xcrunSdk.flatMap(
        (sdk) -> {
          Optional<Path> developerDir = rustBuckConfig.getAppleDeveloperDirIfSet();
          ImmutableMap<String, String> environment;
          if (developerDir.isPresent()) {
            environment = ImmutableMap.of("DEVELOPER_DIR", developerDir.get().toString());
          } else {
            environment = ImmutableMap.of();
          }
          ProcessExecutorParams processExecutorParams =
              ProcessExecutorParams.builder()
                  .setCommand(
                      ImmutableList.of(
                          rustBuckConfig
                              .getAppleXcrunPath()
                              .map((path) -> path.toString())
                              .orElse("xcrun"),
                          "--sdk",
                          sdk,
                          "--show-sdk-path"))
                  .setEnvironment(environment)
                  .build();
          // Must specify that stdout is expected or else output may be wrapped in Ansi escape
          // chars.
          Set<Option> options = EnumSet.of(Option.EXPECTING_STD_OUT);
          Result result;
          try {
            result =
                processExecutor.launchAndExecute(
                    processExecutorParams,
                    options,
                    /* stdin */ Optional.empty(),
                    /* timeOutMs */ Optional.empty(),
                    /* timeOutHandler */ Optional.empty());
          } catch (InterruptedException | IOException e) {
            LOG.warn("Could not execute xcrun, continuing without sdk path.");
            return Optional.empty();
          }

          if (result.getExitCode() != 0) {
            throw new RuntimeException(
                result.getMessageForUnexpectedResult("xcrun --print-sdk-path"));
          }

          return Optional.of(Paths.get(result.getStdout().get().trim()));
        });
  }

  private static Optional<String> getXcrunSdk(Flavor platformFlavor) {
    String platformFlavorName = platformFlavor.getName();
    if (platformFlavorName.startsWith("iphoneos-")) {
      return Optional.of("iphoneos");
    }
    if (platformFlavorName.startsWith("iphonesimulator-")) {
      return Optional.of("iphonesimulator");
    }
    if (platformFlavorName.startsWith("appletvos")) {
      return Optional.of("appletvos");
    }
    if (platformFlavorName.startsWith("watchos")) {
      return Optional.of("watchos");
    }
    return Optional.empty();
  }
}
