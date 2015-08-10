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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClangPreprocessor implements Preprocessor {

  private static final String PRAGMA_TOKEN = "_Pragma";
  private static final String PRAGMA_TOKEN_PLACEHOLDER = "__BUCK__PRAGMA_PLACEHOLDER__";
  private static final Pattern PRAGMA_TOKEN_PLACEHOLDER_PATTERN = Pattern
      .compile("(.*?)" + PRAGMA_TOKEN_PLACEHOLDER + "\\(\"(.*?)\"\\)(.*?)");

  private final Tool tool;

  public ClangPreprocessor(Tool tool) {
    this.tool = tool;
  }

  @Override
  public Optional<ImmutableList<String>> getExtraFlags() {
    return Optional.of(
        ImmutableList.of(
            "-Wno-builtin-macro-redefined",
            "-Wno-error=builtin-macro-redefined",
            "-D" + PRAGMA_TOKEN + "=" + PRAGMA_TOKEN_PLACEHOLDER));
  }

  @Override
  public Optional<Function<String, Iterable<String>>> getExtraLineProcessor() {
    return Optional.<Function<String, Iterable<String>>>of(
        new Function<String, Iterable<String>>() {
          @Override
          public Iterable<String> apply(String input) {
            String remainder = input;
            ImmutableList.Builder<String> processedLines = ImmutableList.builder();
            while (!remainder.isEmpty()) {
              Matcher m = PRAGMA_TOKEN_PLACEHOLDER_PATTERN.matcher(remainder);
              if (!m.matches()) {
                processedLines.add(remainder);
                break;
              }
              processedLines.add(m.group(1));
              processedLines.add("#pragma " + m.group(2).replaceAll("\\\\\"", "\""));
              remainder = m.group(3);
            }

            return processedLines.build();
          }
        }
    );
  }

  @Override
  public boolean supportsHeaderMaps() {
    return true;
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return tool.getDeps(resolver);
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return tool.getInputs();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return tool.getCommandPrefix(resolver);
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("tool", tool)
        .setReflectively("type", getClass().getSimpleName());
  }
}
