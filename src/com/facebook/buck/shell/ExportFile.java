/*
 * Copyright 2012-present Facebook, Inc.
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
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.Collections;

/**
 * Export a file so that it can be easily referenced by other
 * {@link com.facebook.buck.rules.BuildRule}s. There are several valid ways of using export_file
 * (all examples in a build file located at "path/to/buck/BUCK").
 *
 * The most common usage of export_file is:
 * <pre>
 *   export_file(name = 'some-file.html')
 * </pre>
 * This is equivalent to:
 * <pre>
 *   export_file(name = 'some-file.html',
 *     src = 'some-file.html',
 *     out = 'some-file.html')
 * </pre>
 * This results in "//path/to/buck:some-file.html" as the rule, and will export the file
 * "some-file.html" as "some-file.html".
 * <pre>
 *   export_file(
 *     name = 'foobar.html',
 *     src = 'some-file.html',
 *   )
 * </pre>
 * Is equivalent to:
 * <pre>
 *    export_file(name = 'foobar.html', src = 'some-file.html', out = 'foobar.html')
 * </pre>
 * Finally, it's possible to refer to the exported file with a logical name, while controlling the
 * actual file name. For example:
 * <pre>
 *   export_file(name = 'ie-exports',
 *     src = 'some-file.js',
 *     out = 'some-file-ie.js',
 *   )
 * </pre>
 * As a rule of thumb, if the "out" parameter is missing, the "name" parameter is used as the name
 * of the file to be saved.
 */
// TODO(simons): Extend to also allow exporting a rule.
public class ExportFile extends AbstractBuildRule implements HasOutputName {

  private final String name;
  private final SourcePath src;
  private final Path out;

  @VisibleForTesting
  ExportFile(BuildRuleParams params, SourcePathResolver resolver, ExportFileDescription.Arg args) {
    super(params, resolver);
    BuildTarget target = params.getBuildTarget();

    this.name = args.out.or(target.getShortNameAndFlavorPostfix());

    if (args.src.isPresent()) {
      this.src = args.src.get();
    } else {
      this.src = new PathSourcePath(
          target.getBasePath().resolve(target.getShortNameAndFlavorPostfix()));
    }

    this.out = BuckConstant.GEN_PATH.resolve(target.getBasePath()).resolve(this.name);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(Collections.singleton(src));
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("out", out.toString());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    // This file is copied rather than symlinked so that when it is included in an archive zip and
    // unpacked on another machine, it is an ordinary file in both scenarios.
    ImmutableList.Builder<Step> builder = ImmutableList.<Step>builder()
        .add(new MkdirStep(out.getParent()))
        .add(CopyStep.forFile(getResolver().getPath(src), out));

    buildableContext.recordArtifact(out);
    return builder.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return out;
  }

  @Override
  public String getOutputName() {
    return name;
  }

}
