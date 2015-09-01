/*
 * Copyright 2014-present Facebook, Inc.
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

import static com.google.common.base.Predicates.notNull;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class CxxLink
    extends AbstractBuildRule
    implements RuleKeyAppendable, SupportsInputBasedRuleKey {

  @AddToRuleKey
  private final Linker linker;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableList<SourcePath> inputs;
  // We need to make sure we sanitize paths in the arguments, so add them to the rule key
  // in `appendToRuleKey` where we can first filter the args through the sanitizer.
  private final ImmutableList<String> args;
  private final ImmutableSet<Path> frameworkRoots;
  private final ImmutableSet<Path> libraries;
  private final DebugPathSanitizer sanitizer;

  public CxxLink(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Linker linker,
      Path output,
      ImmutableList<SourcePath> inputs,
      ImmutableList<String> args,
      ImmutableSet<Path> frameworkRoots,
      ImmutableSet<Path> libraries,
      DebugPathSanitizer sanitizer) {
    super(params, resolver);
    this.linker = linker;
    this.output = output;
    this.inputs = inputs;
    this.args = args;
    this.frameworkRoots = frameworkRoots;
    this.libraries = libraries;
    this.sanitizer = sanitizer;
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder
        .setReflectively(
            "args",
            FluentIterable.from(args)
                .transform(sanitizer.sanitize(Optional.<Path>absent()))
                .toList())
        .setReflectively(
            "frameworkRoots",
            FluentIterable.from(frameworkRoots)
                .transform(Functions.toStringFunction())
                .transform(sanitizer.sanitize(Optional.<Path>absent()))
                .toList())
        .setReflectively(
            "libraries",
            FluentIterable.from(libraries)
                .transform(Functions.toStringFunction())
                .transform(sanitizer.sanitize(Optional.<Path>absent()))
                .toList());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        new CxxLinkStep(
            getProjectFilesystem().getRootPath(),
            linker.getCommandPrefix(getResolver()),
            output,
            args,
            frameworkRoots,
            getLibrarySearchDirectories(),
            getLibraryNames()),
        new FileScrubberStep(output, linker.getScrubbers(context.getProjectRoot())));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  public Tool getLinker() {
    return linker;
  }

  public Path getOutput() {
    return output;
  }

  public ImmutableList<String> getArgs() {
    return args;
  }

  @VisibleForTesting
  ImmutableList<SourcePath> getInputs() {
    return inputs;
  }

  private ImmutableSet<Path> getLibrarySearchDirectories() {
    return FluentIterable.from(libraries)
        .transform(
            new Function<Path, Path>() {
              @Nullable
              @Override
              public Path apply(Path input) {
                return input.getParent();
              }
            }
        ).filter(notNull())
        .toSet();
  }

  private ImmutableSet<String> getLibraryNames() {
    return FluentIterable.from(libraries)
        .transform(
            new Function<Path, String>() {
              @Override
              public String apply(Path fileName) {
                return MorePaths.stripPathPrefixAndExtension(fileName, "lib");
              }
            }
            // libraries set can contain path-qualified libraries, or just library search paths.
            // Assume these end in '../lib' and filter out here.
        ).filter(MoreStrings.NON_EMPTY)
        .toSet();
    }
  }
