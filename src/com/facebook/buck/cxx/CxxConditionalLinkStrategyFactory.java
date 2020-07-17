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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/**
 * Defines the factory interface to create linking strategy. Used by {@link CxxLinkableEnhancer} to
 * create the {@link CxxConditionalLinkStrategy} that will be used to perform linking.
 */
public interface CxxConditionalLinkStrategyFactory {

  /**
   * Creates a strategy that will be used by {@link CxxLink} for linking.
   *
   * @param ldArgs The list of arguments given to the linker.
   * @param outputPath The path to the linked executable.
   */
  CxxConditionalLinkStrategy createStrategy(
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      BuildTarget target,
      ImmutableList<Arg> ldArgs,
      Linker linker,
      Path outputPath);
}
