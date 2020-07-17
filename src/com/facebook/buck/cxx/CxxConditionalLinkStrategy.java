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

package com.facebook.buck.cxx;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * {@link CxxConditionalLinkStrategy} provides the necessary details to be able to implement a
 * technique named "Conditional Relinking" whereby linking can be skipped.
 *
 * <p>More specifically, linking of executables can be skipped in certain circumstances: for
 * example, if all linked shared libraries change in a way that does _not_ change what the bound
 * symbols of the currently linked executable would resolve to.
 *
 * <p>For example, imagine a linked shared library removes an exported symbol which was not actually
 * bound by the currently linked executable.
 */
public interface CxxConditionalLinkStrategy extends AddsToRuleKey {

  /**
   * Defines whether the strategy performs any incremental work. If this method returns {@code
   * false}, then no further methods will be called on the object.
   */
  boolean isIncremental();

  /**
   * Returns a list of {@link OutputPath}s that will be excluded from the automatic MBR cleanup
   * behavior whereby any output files get removed before any build steps execute.
   */
  ImmutableList<OutputPath> getExcludedOutpathPathsFromAutomaticRemoval();

  /**
   * Creates a list of steps which check if linking should be skipped. If that's the case, the steps
   * should write an empty file to the {@code skipLinkingPath} which will prevent the linking step
   * from executing. The relink check is also responsible for updating the relinking info, placed at
   * {@code relinkInfoPath}.
   *
   * @param argfilePath Path to the argfile passed to the linker.
   * @param filelistPath Path to the filelist given to the linker.
   * @param skipLinkingPath If path exists, indicates that linking should be skipped.
   * @param linkedExecutablePath The path to the executable being linked.
   */
  ImmutableList<Step> createConditionalLinkCheckSteps(
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      SourcePathResolverAdapter sourcePathResolver,
      AbsPath argfilePath,
      AbsPath filelistPath,
      AbsPath skipLinkingPath,
      RelPath linkedExecutablePath,
      ImmutableMap<String, String> environment,
      ImmutableList<String> linkerCommandPrefix);

  /**
   * Creates list of steps which write information needed to determine whether linking can be
   * skipped. The information written to {@code relinkInfoPath} will be used by the relink check
   * steps in subsequent link operations.
   *
   * @param argfilePath Path to the argfile passed to the linker.
   * @param filelistPath Path to the filelist given to the linker.
   * @param skipLinkingPath If path exists, indicates that linking should be skipped.
   * @param linkedExecutablePath The path to the executable being linked.
   */
  ImmutableList<Step> createConditionalLinkWriteSteps(
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      SourcePathResolverAdapter sourcePathResolver,
      AbsPath argfilePath,
      AbsPath filelistPath,
      AbsPath skipLinkingPath,
      RelPath linkedExecutablePath,
      ImmutableMap<String, String> environment,
      ImmutableList<String> linkerCommandPrefix);
}
