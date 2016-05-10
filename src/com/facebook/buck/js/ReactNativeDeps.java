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

package com.facebook.buck.js;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

public class ReactNativeDeps extends AbstractBuildRule
    implements InitializableFromDisk<ReactNativeDeps.BuildOutput> {

  private static final String METADATA_KEY_FOR_INPUTS_HASH = "js_inputs_hash";

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;

  @AddToRuleKey
  private final SourcePath entryPath;

  @AddToRuleKey
  private final ReactNativePlatform platform;

  @AddToRuleKey
  private final Optional<String> packagerFlags;

  @AddToRuleKey
  private final Tool jsPackager;

  private final Path outputDir;
  private final Path inputsHashFile;

  private final BuildOutputInitializer<BuildOutput> outputInitializer;

  public ReactNativeDeps(
      BuildRuleParams ruleParams,
      SourcePathResolver resolver,
      Tool jsPackager,
      ImmutableSortedSet<SourcePath> srcs,
      SourcePath entryPath,
      ReactNativePlatform platform,
      Optional<String> packagerFlags) {
    super(ruleParams, resolver);
    this.jsPackager = jsPackager;
    this.srcs = srcs;
    this.entryPath = entryPath;
    this.platform = platform;
    this.packagerFlags = packagerFlags;
    this.outputDir = BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
    this.inputsHashFile = outputDir.resolve("inputs_hash.txt");
    this.outputInitializer = new BuildOutputInitializer<>(ruleParams.getBuildTarget(), this);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    final Path output =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "__%s/deps.txt");
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), output.getParent()));

    appendWorkerSteps(steps, output);

    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDir));

    steps.add(new AbstractExecutionStep("hash_js_inputs") {
      @Override
      public int execute(ExecutionContext context) throws IOException {
        ImmutableList<Path> paths;
        try {
          paths = FluentIterable.from(getProjectFilesystem().readLines(output))
              .transform(MorePaths.TO_PATH)
              .transform(getProjectFilesystem().getRelativizer())
              .toSortedList(Ordering.natural());
        } catch (IOException e) {
          context.logError(e, "Error reading output of the 'react-native-deps' step.");
          return 1;
        }

        FluentIterable<SourcePath> unlistedSrcs =
            FluentIterable.from(paths).transform(SourcePaths.toSourcePath(getProjectFilesystem()))
                .filter(Predicates.not(Predicates.in(srcs)));
        if (!unlistedSrcs.isEmpty()) {
          context.logError(
              new RuntimeException(),
              "Entry path '%s' transitively uses the following source files which were not " +
                  "included in 'srcs':\n%s",
              entryPath,
              Joiner.on('\n').join(unlistedSrcs));
          return 1;
        }

        Hasher hasher = Hashing.sha1().newHasher();
        for (Path path : paths) {
          try {
            hasher.putUnencodedChars(getProjectFilesystem().computeSha1(path));
          } catch (IOException e) {
            context.logError(e, "Error hashing input file: %s", path);
            return 1;
          }
        }

        String inputsHash = hasher.hash().toString();
        buildableContext.addMetadata(METADATA_KEY_FOR_INPUTS_HASH, inputsHash);
        getProjectFilesystem().writeContentsToPath(inputsHash, inputsHashFile);
        return 0;
      }
    });

    return steps.build();
  }

  private void appendWorkerSteps(ImmutableList.Builder<Step> stepBuilder, Path outputFile) {
    final Path tmpDir =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s__tmp");
    stepBuilder.add(new MakeCleanDirectoryStep(getProjectFilesystem(), tmpDir));
    ReactNativeDepsWorkerStep workerStep = new ReactNativeDepsWorkerStep(
        getProjectFilesystem(),
        tmpDir,
        jsPackager.getCommandPrefix(getResolver()),
        packagerFlags,
        platform,
        getProjectFilesystem().resolve(getResolver().getAbsolutePath(entryPath)),
        getProjectFilesystem().resolve(outputFile));
    stepBuilder.add(workerStep);
  }

  @Override
  @Nullable
  public Path getPathToOutput() {
    return outputDir;
  }

  public Sha1HashCode getInputsHash() {
    return outputInitializer.getBuildOutput().inputsHash;
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    Optional<Sha1HashCode> hash = onDiskBuildInfo.getHash(METADATA_KEY_FOR_INPUTS_HASH);
    Preconditions.checkState(hash.isPresent());
    return new BuildOutput(hash.get());
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return outputInitializer;
  }

  public static class BuildOutput {
    private final Sha1HashCode inputsHash;

    private BuildOutput(Sha1HashCode hash) {
      this.inputsHash = hash;
    }
  }
}
