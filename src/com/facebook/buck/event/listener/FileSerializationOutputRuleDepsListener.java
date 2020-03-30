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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.build.event.BuildRuleExecutionEvent;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link BuckEventListener} that subscribes to {@link BuildRuleExecutionEvent.Finished} events and
 * serialize rule build target, rule type, rule execution time, rule's dependencies to outputFile in
 * csv format. This file is intended to be used in the rule execution simulator.
 */
public class FileSerializationOutputRuleDepsListener
    extends AbstractFileSerializationBuckEventListener {

  public FileSerializationOutputRuleDepsListener(Path outputPath) throws IOException {
    super(outputPath);
  }

  /**
   * Subscribes to {@link BuildRuleExecutionEvent.Finished} events. Convert this event into json
   * representation and serialize as a line to {@code outputPath}.
   */
  @Subscribe
  public void subscribe(BuildRuleExecutionEvent.Finished event) {
    String targetId = event.getTarget().getFullyQualifiedName();
    long elapsedTimeMillis = TimeUnit.NANOSECONDS.toMillis(event.getElapsedTimeNano());
    RuleExecutionTimeData ruleExecutionTimeData =
        ImmutableRuleExecutionTimeData.of(targetId, elapsedTimeMillis);

    convertToJson(ruleExecutionTimeData).ifPresent(this::scheduleWrite);
  }

  Optional<String> convertToJson(RuleExecutionTimeData ruleExecutionTimeData) {
    try {
      return Optional.of(ObjectMappers.WRITER.writeValueAsString(ruleExecutionTimeData));
    } catch (IOException e) {
      LOG.error(e, "I/O exception during serializing data %s to json", ruleExecutionTimeData);
    }
    return Optional.empty();
  }

  @Override
  public void close() {
    // do nothing. do not need to close writer at that moment because we still can receive events
  }

  // i/o stream should be closed only after we finished command execution
  @Subscribe
  public void commandFinished(CommandEvent.Finished event) {
    LOG.info("Received command finished event for command : %s", event.getCommandName());
    super.close();
  }

  /** Data object that is used to serialize rule execution information into a file */
  @BuckStyleValue
  @JsonSerialize
  @JsonDeserialize(as = ImmutableRuleExecutionTimeData.class)
  public interface RuleExecutionTimeData {

    String getTargetId();

    long getElapsedTimeMs();
  }
}
