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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.cxx.toolchain.objectfile.ObjectFileScrubbers;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Computes the hash of a file and writes it out as the output. Includes special configurable logic
 * to extract UUIDs from Mach-O files, which can be used instead, as to avoid re-hashing.
 */
public class AppleWriteFileHash extends ModernBuildRule<AppleWriteFileHash> implements Buildable {
  @AddToRuleKey private final SourcePath inputPath;
  @AddToRuleKey private final OutputPath outputPath;
  @AddToRuleKey private final boolean useMachoUuid;

  public AppleWriteFileHash(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath pathToFile,
      boolean useMachoUuid) {
    super(buildTarget, projectFilesystem, ruleFinder, AppleWriteFileHash.class);
    this.inputPath = pathToFile;
    this.outputPath = new OutputPath("file.apple_hash");
    this.useMachoUuid = useMachoUuid;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    return ImmutableList.of(
        new AbstractExecutionStep("writing_apple_file_hash") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context) throws IOException {
            filesystem.writeContentsToPath(
                computeHash(), outputPathResolver.resolvePath(outputPath));
            return StepExecutionResults.SUCCESS;
          }

          private String computeHash() throws IOException {
            AbsPath absInputPath = buildContext.getSourcePathResolver().getAbsolutePath(inputPath);

            if (useMachoUuid) {
              Optional<String> maybeUuid = getMachoUuid(absInputPath);
              if (maybeUuid.isPresent()) {
                return "uuid:" + maybeUuid.get();
              }
            }

            String sha1 = filesystem.computeSha1(absInputPath.getPath()).getHash();
            // In order to guarantee no collisions between Mach-O and non-Mach-O files,
            // we namespace them with a suitable prefix.
            return useMachoUuid ? ("sha1:" + sha1) : sha1;
          }

          private Optional<String> getMachoUuid(AbsPath path) throws IOException {
            try (FileChannel file = FileChannel.open(path.getPath(), StandardOpenOption.READ)) {
              if (!Machos.isMacho(file)) {
                return Optional.empty();
              }

              try (ByteBufferUnmapper unmapper =
                  ByteBufferUnmapper.createUnsafe(
                      file.map(FileChannel.MapMode.READ_ONLY, 0, file.size()))) {

                try {
                  Optional<byte[]> maybeUuid = Machos.getUuidIfPresent(unmapper.getByteBuffer());
                  if (maybeUuid.isPresent()) {
                    String hexBytes = ObjectFileScrubbers.bytesToHex(maybeUuid.get(), true);
                    return Optional.of(hexBytes);
                  }
                } catch (Machos.MachoException e) {
                  // Even though it's a Mach-O file, we failed to read it safely
                  throw new RuntimeException("Internal Mach-O file parsing failure");
                }

                return Optional.empty();
              }
            }
          }
        });
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(outputPath);
  }
}
