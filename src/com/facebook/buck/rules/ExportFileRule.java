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

package com.facebook.buck.rules;

import com.facebook.buck.shell.Command;
import com.facebook.buck.shell.MkdirAndSymlinkFileCommand;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Export a file so that it can be easily referenced by other {@link BuildRule}s. There are several
 * valid ways of using export_file (all examples in a build file located at "path/to/buck/BUCK").
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
public class ExportFileRule extends AbstractCachingBuildRule  {

  private final String src;
  private final Supplier<File> out;


  @VisibleForTesting
  ExportFileRule(BuildRuleParams params, Optional<String> src, Optional<String> out) {
    super(params);

    String shortName = params.getBuildTarget().getShortName();

    this.src = src.or(shortName);

    final String outName = out.or(shortName);

    this.out = Suppliers.memoize(new Supplier<File>() {
      @Override
      public File get() {
        String name = new File(outName).getName();
        String outputPath = String.format("%s/%s%s",
            BuckConstant.GEN_DIR,
            getBuildTarget().getBasePathWithSlash(),
            name);

        return new File(outputPath);
      }
    });
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.EXPORT_FILE;
  }

  @Override
  protected Iterable<String> getInputsToCompareToOutput(BuildContext context) {
    return ImmutableSet.of(src);
  }

  @Override
  protected List<Command> buildInternal(BuildContext context) throws IOException {
    File inputFile = context.getProjectFilesystem().getFileForRelativePath(src);
    File outputFile = out.get();

    ImmutableList.Builder<Command> builder = ImmutableList.<Command>builder()
        .add(new MkdirAndSymlinkFileCommand(inputFile, outputFile));

    return builder.build();
  }

  @Nullable
  @Override
  public File getOutput() {
    return out.get();
  }

  public static Builder newExportFileBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder {
    private Optional<String> src;
    private Optional<String> out;

    public Builder setSrc(Optional<String> src) {
      this.src = src;
      return this;
    }

    public Builder setOut(Optional<String> out) {
      this.out = out;
      return this;
    }

    @Override
    public ExportFileRule build(Map<String, BuildRule> buildRuleIndex) {
      BuildRuleParams params = createBuildRuleParams(buildRuleIndex);

      return new ExportFileRule(params, src, out);
    }
  }
}
