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

package com.facebook.buck.cxx;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.DependencyTrackingMode;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Helper class for generating compiler invocations for a cxx compilation rule. */
class CompilerDelegate implements AddsToRuleKey {
  // Fields that are added to rule key as is.
  @AddToRuleKey private final Compiler compiler;
  @AddToRuleKey private final CxxToolFlags compilerFlags;
  @AddToRuleKey private final DebugPathSanitizer sanitizer;
  @AddToRuleKey private final Optional<Boolean> useArgFile;

  public CompilerDelegate(
      DebugPathSanitizer sanitizer,
      Compiler compiler,
      CxxToolFlags flags,
      Optional<Boolean> useArgFile) {
    this.sanitizer = sanitizer;
    this.compiler = compiler;
    this.compilerFlags = flags;
    this.useArgFile = useArgFile;
  }

  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return compiler.getCommandPrefix(resolver);
  }

  public ImmutableList<Arg> getArguments(CxxToolFlags prependedFlags, Path cellPath) {
    return ImmutableList.<Arg>builder()
        .addAll(CxxToolFlags.concat(prependedFlags, compilerFlags).getAllFlags())
        .addAll(
            StringArg.from(
                compiler.getFlagsForReproducibleBuild(
                    sanitizer.getCompilationDirectory(), cellPath)))
        .build();
  }

  public CxxToolFlags getCompilerFlags() {
    return compilerFlags;
  }

  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return compiler.getEnvironment(resolver);
  }

  public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
    Stream.Builder<SourcePath> inputs = Stream.builder();

    // Add inputs from the compiler object.
    BuildableSupport.deriveInputs(compiler).sorted().forEach(inputs);

    // Args can contain things like location macros, so extract any inputs we find.
    for (Arg arg : compilerFlags.getAllFlags()) {
      BuildableSupport.deriveInputs(arg).forEach(inputs);
    }

    return inputs
        .build()
        .filter(
            (SourcePath path) ->
                !(path instanceof PathSourcePath)
                    || !((PathSourcePath) path).getRelativePath().isAbsolute())
        .collect(ImmutableList.toImmutableList());
  }

  public boolean isArgFileSupported() {
    return useArgFile.orElse(compiler.isArgFileSupported());
  }

  public DependencyTrackingMode getDependencyTrackingMode() {
    return compiler.getDependencyTrackingMode();
  }

  public Compiler getCompiler() {
    return compiler;
  }

  public Iterable<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    deps.addAll(BuildableSupport.getDepsCollection(getCompiler(), ruleFinder));
    RichStream.from(getCompilerFlags().getAllFlags())
        .flatMap(a -> BuildableSupport.getDeps(a, ruleFinder))
        .forEach(deps::add);
    return deps.build();
  }

  public Predicate<SourcePath> getCoveredByDepFilePredicate() {
    // TODO(cjhopman): this should not include tools (an actual compiler)
    return (SourcePath path) ->
        !(path instanceof PathSourcePath)
            || !((PathSourcePath) path).getRelativePath().isAbsolute();
  }
}
