/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.step.fs;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.stringtemplate.v4.ST;

/**
 * A step that creates an {@link ST} by reading a template from {@code templatePath}, calls {@code
 * configure} to configure it, renders it and writes it out to {@code outputPath}.
 */
public class StringTemplateStep implements Step {

  private final Path templatePath;
  private final ProjectFilesystem filesystem;
  private final Path outputPath;
  private final ImmutableMap<String, ?> values;

  public StringTemplateStep(
      Path templatePath,
      ProjectFilesystem filesystem,
      Path outputPath,
      ImmutableMap<String, ?> values) {
    Preconditions.checkArgument(
        !outputPath.isAbsolute(), "Output must be specified as a relative path: %s", outputPath);
    this.templatePath = templatePath;
    this.filesystem = filesystem;
    this.outputPath = outputPath;
    this.values = values;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    String template;
    template = new String(Files.readAllBytes(templatePath), Charsets.UTF_8);

    ST st = new ST(template);

    for (Map.Entry<String, ?> ent : values.entrySet()) {
      st = st.add(ent.getKey(), ent.getValue());
    }

    return new WriteFileStep(
            filesystem, Objects.requireNonNull(st.render()), outputPath, /* executable */ false)
        .execute(context);
  }

  @Override
  public String getShortName() {
    return "stringtemplate";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("Render template '%s' to '%s'", templatePath, outputPath);
  }
}
