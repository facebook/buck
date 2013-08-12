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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.ZipStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Base class for rules which produce an archive of files.
 */
public abstract class ArchivingRule extends AbstractCachingBuildRule {

  private final String outputZip;

  protected ArchivingRule(BuildRuleParams buildRuleParams) {
    super(buildRuleParams);
    this.outputZip = getPathToOutputZip(getBuildTarget());
  }

  /**
   * @return The path to the zip file containing the output of building the native library.
   */
  private static String getPathToOutputZip(BuildTarget target) {
    return String.format("%s/%s__%s_archive/%s.zip",
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash(),
        target.getShortName(),
        target.getShortName());
  }

  @Override
  public String getPathToOutputFile() {
    return outputZip;
  }

  /**
   * @return The path to a directory to archive.
   */
  abstract protected Path getArchivePath();

  abstract protected List<Step> buildArchive(BuildContext context) throws IOException;

  @Override
  public final List<Step> getBuildSteps(BuildContext context) throws IOException {
    String pathToOutputFile = getPathToOutputFile();
    String outputParentDirectory = new File(pathToOutputFile).getParent();

    return ImmutableList.<Step>builder()
        .addAll(buildArchive(context))
        .add(new MakeCleanDirectoryStep(outputParentDirectory))
        .add(new ZipStep(new File(getPathToOutputFile()), getArchivePath().toFile()))
        .build();
  }
}
