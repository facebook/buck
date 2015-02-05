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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;

import org.stringtemplate.v4.ST;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A step that creates an {@link ST} by reading a template from {@code templatePath}, calls
 * {@code configure} to configure it, renders it and writes it out to {@code outputPath}.
 */
public class StringTemplateStep implements Step {

  private final Path templatePath;
  private final Path outputPath;
  private final Function<ST, ST> configure;

  public StringTemplateStep(Path templatePath, Path outputPath, Function<ST, ST> configure) {
    this.templatePath = templatePath;
    this.outputPath = outputPath;
    this.configure = configure;
  }

  @Override
  public int execute(ExecutionContext context) throws IOException, InterruptedException {
    String template;
    try {
      template = new String(Files.readAllBytes(templatePath), Charsets.UTF_8);
    } catch (IOException e) {
      context.logError(e, "Could not read sh_binary template file");
      return 1;
    }

    ST st = new ST(template);

    return new WriteFileStep(Preconditions.checkNotNull(configure.apply(st).render()), outputPath)
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
