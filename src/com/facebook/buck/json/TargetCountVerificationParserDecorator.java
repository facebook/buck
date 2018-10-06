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

package com.facebook.buck.json;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ForwardingProjectBuildFileParserDecorator;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Delegates to the aggregated parser to do the parsing, while warning the numbers of targets
 * exceeds a threshold.
 */
public class TargetCountVerificationParserDecorator
    extends ForwardingProjectBuildFileParserDecorator {
  private final int targetWarnCount;
  private final BuckEventBus buckEventBus;

  /**
   * @param delegate the aggregated parser.
   * @param targetWarnCount the count of target which, if exceeded will log a warning.
   * @param eventBus The event buss where to post warning events for handling.
   */
  public TargetCountVerificationParserDecorator(
      ProjectBuildFileParser delegate, int targetWarnCount, BuckEventBus eventBus) {
    super(delegate);
    Objects.requireNonNull(eventBus, "Must have a valid eventBus set.");
    this.targetWarnCount = targetWarnCount;
    buckEventBus = eventBus;
  }

  /**
   * Warns about more than reasonable amount of targets created by parsing the expanded buildFile.
   *
   * @param buildFile build file which parsing produces that many targets.
   * @param targetCount the count of targets produced.
   */
  private void maybePostWarningAboutTooManyTargets(Path buildFile, int targetCount) {
    if (targetCount <= targetWarnCount) {
      // No warning should be emitted.
      return;
    }

    buckEventBus.post(
        ConsoleEvent.warning(
            String.format(
                "Number of expanded targets - %1$d - in file %2$s exceeds the threshold of %3$d. This could result in really slow builds.",
                targetCount, buildFile.toString(), targetWarnCount)));
  }

  @Override
  public BuildFileManifest getBuildFileManifest(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    BuildFileManifest targetManifest = delegate.getBuildFileManifest(buildFile);
    maybePostWarningAboutTooManyTargets(buildFile, targetManifest.getTargets().size());
    return targetManifest;
  }
}
