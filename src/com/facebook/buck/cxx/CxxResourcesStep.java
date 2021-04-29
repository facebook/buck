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

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableMap;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;

/** Generate a JSON map of C++ resource names to their paths on disk. */
public class CxxResourcesStep extends IsolatedStep {

  private final RelPath output;
  private final AbsPath relativeFrom;
  private final ImmutableMap<ForwardRelPath, AbsPath> resolvedResources;

  /**
   * @param output path to write JSON file to.
   * @param relativeFrom relativize source paths from this starting point.
   * @param resolvedResources map of resource to serialize.
   */
  public CxxResourcesStep(
      RelPath output,
      AbsPath relativeFrom,
      ImmutableMap<ForwardRelPath, AbsPath> resolvedResources) {
    this.output = output;
    this.relativeFrom = relativeFrom;
    this.resolvedResources = resolvedResources;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    try (OutputStream stream =
        new BufferedOutputStream(
            Files.newOutputStream(context.getRuleCellRoot().resolve(output.getPath()).getPath()))) {
      JsonFactory factory = new JsonFactory();
      try (JsonGenerator generator = factory.createGenerator(stream, JsonEncoding.UTF8)) {
        generator.writeStartObject();
        for (Map.Entry<ForwardRelPath, AbsPath> ent : resolvedResources.entrySet()) {
          generator.writeStringField(
              ent.getKey().toString(), relativeFrom.relativize(ent.getValue()).toString());
        }
        generator.writeEndObject();
      }
    }
    return StepExecutionResult.of(0);
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return "Generate a JSON map of C++ resources";
  }

  @Override
  public String getShortName() {
    return "cxx-resources-json";
  }
}
