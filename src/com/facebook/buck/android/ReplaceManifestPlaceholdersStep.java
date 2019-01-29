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

package com.facebook.buck.android;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces placeholders in the android manifest. */
public class ReplaceManifestPlaceholdersStep implements Step {

  private final ProjectFilesystem projectFilesystem;
  private final Path androidManifest;
  private final Path replacedManifest;
  private final ImmutableMap<String, String> manifestEntries;

  public ReplaceManifestPlaceholdersStep(
      ProjectFilesystem projectFilesystem,
      Path androidManifest,
      Path replacedManifest,
      ImmutableMap<String, String> manifestEntries) {
    this.projectFilesystem = projectFilesystem;
    this.androidManifest = androidManifest;
    this.replacedManifest = replacedManifest;
    this.manifestEntries = manifestEntries;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    String content =
        new String(
            Files.readAllBytes(projectFilesystem.resolve(androidManifest)), StandardCharsets.UTF_8);
    String replaced = replacePlaceholders(content, manifestEntries);
    projectFilesystem.writeContentsToPath(replaced, replacedManifest);
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "replace_manifest_placeholders";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("%s: %s -> %s", getShortName(), androidManifest, replacedManifest);
  }

  @VisibleForTesting
  static String replacePlaceholders(String content, ImmutableMap<String, String> placeholders) {
    Iterable<String> escaped = Iterables.transform(placeholders.keySet(), Pattern::quote);

    Joiner joiner = Joiner.on("|");
    String patternString =
        Pattern.quote("${") + "(" + joiner.join(escaped) + ")" + Pattern.quote("}");
    Pattern pattern = Pattern.compile(patternString);
    Matcher matcher = pattern.matcher(content);

    StringBuffer sb = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(sb, placeholders.get(matcher.group(1)));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
