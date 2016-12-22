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

package com.facebook.buck.rust;

import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.SymlinkFilesIntoDirectoryStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Work out how to invoke the Rust compiler, rustc.
 *
 * In Rust, a crate is the equivalent of a package in other languages. It's also the basic unit of
 * compilation.
 *
 * A crate can either be a "binary crate" - which generates an executable - or a "library crate",
 * which makes an .rlib file. .rlib files contain both interface details (function signatures,
 * inline functions, macros, etc) and compiled object code, and so are equivalent to both header
 * files and library archives. .rdyn files also exist, which are dynamically linked .rlib files,
 * but they are very rarely used (rustc compiler plugins being the only common example).
 *
 * All crates are compiled from at least one source file, which is its main (or top, or root)
 * module. It may have references to other modules, which may be in other source files. Rustc only
 * needs the main module filename and will find the rest of the source files from there (akin to
 * #include in C/C++). If the crate also has dependencies on other crates, then those .rlib files
 * must also be passed to rustc for the interface details, and to be linked if its a binary crate.
 */
abstract class RustCompile extends AbstractBuildRule {
  @AddToRuleKey
  private final Supplier<Tool> compiler;
  @AddToRuleKey
  private final Supplier<Tool> linker;
  @AddToRuleKey
  private final ImmutableList<String> linkerArgs;
  @AddToRuleKey
  private final ImmutableSet<SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableList<String> flags;
  @AddToRuleKey
  private final ImmutableSet<String> features;
  @AddToRuleKey
  private final Linker.LinkableDepType linkStyle;
  @AddToRuleKey
  private final String crate;
  @AddToRuleKey
  private final Optional<SourcePath> crateRoot;

  private final ImmutableSet<Path> nativePaths;

  private final Path scratchDir;
  private final Path output;

  RustCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      String crate,
      Optional<SourcePath> crateRoot,
      ImmutableSet<SourcePath> srcs,
      ImmutableList<String> flags,
      ImmutableSet<String> features,
      ImmutableSet<Path> nativePaths,
      Path output,
      Supplier<Tool> compiler,
      Supplier<Tool> linker,
      ImmutableList<String> linkerArgs,
      Linker.LinkableDepType linkStyle) {
    super(params, resolver);

    this.srcs = srcs;
    this.flags = ImmutableList.<String>builder()
        .add("--crate-name", crate)
        .addAll(flags)
        .build();
    this.features = features;
    this.crate = crate;
    this.crateRoot = crateRoot;
    this.output = output;
    this.compiler = compiler;
    this.linker = linker;
    this.linkerArgs = linkerArgs;
    this.linkStyle = linkStyle;
    this.scratchDir =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s-container");

    this.nativePaths = nativePaths;

    for (String feature : features) {
      if (feature.contains("\"")) {
        throw new HumanReadableException(
            "%s contains an invalid feature name %s",
            getBuildTarget().getFullyQualifiedName(),
            feature);
      }
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableMap.Builder<String, Path> externalCratesBuilder = ImmutableMap.builder();
    ImmutableSet.Builder<Path> externalDepsBuilder = ImmutableSet.builder();

    buildableContext.recordArtifact(output);

    for (BuildRule buildRule : getDeps()) {
      if (buildRule instanceof RustLinkable) {
        RustLinkable linkable = (RustLinkable) buildRule;
        Path ruleOutput = linkable.getLinkPath();
        externalCratesBuilder.put(linkable.getLinkTarget(), ruleOutput);
        externalDepsBuilder.addAll(linkable.getDependencyPaths());
      }
    }

    Tool compiler = this.compiler.get();
    ImmutableList.Builder<String> linkerArgs = ImmutableList.builder();

    linkerArgs.addAll(linker.get().getCommandPrefix(getResolver()));
    linkerArgs.addAll(this.linkerArgs);

    SourcePathResolver resolver = getResolver();

    return ImmutableList.of(
        new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir),
        new SymlinkFilesIntoDirectoryStep(
            getProjectFilesystem(),
            getProjectFilesystem().getRootPath(),
            resolver.getAllRelativePaths(srcs),
            scratchDir),
        new MakeCleanDirectoryStep(getProjectFilesystem(), output.getParent()),
        new RustCompileStep(
            getProjectFilesystem().getRootPath(),
            compiler.getEnvironment(),
            compiler.getCommandPrefix(getResolver()),
            linkerArgs.build(),
            flags,
            features,
            output,
            externalCratesBuilder.build(),
            externalDepsBuilder.build(),
            nativePaths,
            getCrateRoot()));
  }

  /**
   * Return the default top-level source name for a crate of this type.
   *
   * @return The source filename for the top-level module.
   */
  protected abstract ImmutableSet<String> getDefaultSources();

  @VisibleForTesting
  Path getCrateRoot() {
    SourcePathResolver resolver = getResolver();
    Optional<Path> crateRoot = this.crateRoot.map(resolver::getRelativePath);
    String crateName = String.format("%s.rs", getCrateName());
    ImmutableList<Path> candidates = srcs.stream()
        .map(resolver::getRelativePath)
        .filter(Objects::nonNull)
        .filter(p -> crateRoot.map(p::equals).orElse(false) ||
                     p.endsWith(crateName) ||
                     getDefaultSources().contains(p.getFileName().toString()))
        .collect(MoreCollectors.toImmutableList());

    if (candidates.size() != 1) {
      throw new HumanReadableException(
          "srcs of %s must contain either one from %s or %s.rs!",
          getBuildTarget().getFullyQualifiedName(),
          getDefaultSources(),
          crate);
    }
    // We end up creating a symlink tree to ensure that the crate only uses the files that it
    // declares in the BUCK file.
    return scratchDir.resolve(candidates.get(0));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  public Linker.LinkableDepType getLinkStyle() {
    return linkStyle;
  }

  public String getCrateName() {
    return crate;
  }
}
