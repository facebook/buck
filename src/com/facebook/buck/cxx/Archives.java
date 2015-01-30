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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A utility class containing helper methods for working with "ar" archives.
 */
public class Archives {

  private Archives() {}

  private static final BuildRuleType ARCHIVE_TYPE = ImmutableBuildRuleType.of("archive");

  /**
   * Given a {@link com.facebook.buck.model.BuildTarget} identifying an
   * {@link com.facebook.buck.cxx.Archive}, return an output path suitable for use as the
   * generated archive.
   */
  public static Path getArchiveOutputPath(BuildTarget target) {
    return BuildTargets.getGenPath(
        target,
        "%s/lib" + target.getShortName() + ".a");
  }

  /**
   * Construct an {@link com.facebook.buck.cxx.Archive} from a
   * {@link com.facebook.buck.rules.BuildRuleParams} object representing a target
   * node.  In particular, make sure to trim dependencies to *only* those that
   * provide the input {@link com.facebook.buck.rules.SourcePath}.
   */
  public static Archive createArchiveRule(
      SourcePathResolver resolver,
      BuildTarget target,
      BuildRuleParams originalParams,
      Tool archiver,
      Path output,
      ImmutableList<SourcePath> inputs) {

    // Convert the input build params into ones specialized for this archive build rule.
    // In particular, we only depend on BuildRules directly from the input file SourcePaths.
    BuildRuleParams archiveParams = originalParams.copyWithChanges(
        ARCHIVE_TYPE,
        target,
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(resolver.filterBuildRuleInputs(inputs))
                    .addAll(archiver.getBuildRules(resolver))
                    .build()));

    return new Archive(
        archiveParams,
        resolver,
        archiver,
        output,
        inputs);
  }

}
