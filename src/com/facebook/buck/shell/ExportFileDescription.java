/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitInputsInferringDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.Optional;

public class ExportFileDescription implements
    Description<ExportFileDescription.Arg>,
    ImplicitInputsInferringDescription<ExportFileDescription.Arg> {

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> ExportFile createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    BuildTarget target = params.getBuildTarget();

    Mode mode = args.mode.orElse(Mode.COPY);

    String name;
    if (args.out.isPresent()) {
      if (mode == ExportFileDescription.Mode.REFERENCE) {
        throw new HumanReadableException(
            "%s: must not set `out` for `export_file` when using `REFERENCE` mode",
            params.getBuildTarget());
      }
      name = args.out.get();
    } else {
      name = target.getShortNameAndFlavorPostfix();
    }

    SourcePath src;
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    if (args.src.isPresent()) {
      if (mode == ExportFileDescription.Mode.REFERENCE &&
          !pathResolver.getFilesystem(args.src.get()).equals(params.getProjectFilesystem())) {
        throw new HumanReadableException(
            "%s: must use `COPY` mode for `export_file` when source (%s) uses a different cell",
            target,
            args.src.get());
      }
      src = args.src.get();
    } else {
      src =
          new PathSourcePath(
              params.getProjectFilesystem(),
              target.getBasePath().resolve(target.getShortNameAndFlavorPostfix()));
    }

    return new ExportFile(params, pathResolver, ruleFinder, name, mode, src);
  }

  /**
   * If the src field is absent, add the name field to the list of inputs.
   */
  @Override
  public Iterable<Path> inferInputsFromConstructorArgs(
      UnflavoredBuildTarget buildTarget,
      ExportFileDescription.Arg constructorArg) {
    ImmutableList.Builder<Path> inputs = ImmutableList.builder();
    if (!constructorArg.src.isPresent()) {
      inputs.add(buildTarget.getBasePath().resolve(buildTarget.getShortName()));
    }
    return inputs.build();
  }

  /**
   * Controls how `export_file` exports it's wrapped source.
   */
  public enum Mode {

    /**
     * Forward the wrapped {@link SourcePath} reference without any build time overhead (e.g.
     * copying, caching, etc).
     */
    REFERENCE,

    /**
     * Create and export a copy of the wrapped {@link SourcePath} (incurring the cost of copying and
     * caching this copy at build time).
     */
    COPY,

  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<SourcePath> src;
    public Optional<String> out;
    public Optional<Mode> mode;
  }

}
