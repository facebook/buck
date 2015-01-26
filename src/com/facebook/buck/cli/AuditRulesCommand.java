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

package com.facebook.buck.cli;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import javax.annotation.Nullable;

/**
 * Evaluates a build file and prints out an equivalent build file with all includes/macros
 * expanded. When complex macros are in play, this helps clarify what the resulting build rule
 * definitions are.
 */
public class AuditRulesCommand extends AbstractCommandRunner<AuditRulesOptions> {

  /** Indent to use in generated build file. */
  private static final String INDENT = "  ";

  /**
   * Properties from the JSON produced by {@code buck.py} that start with this prefix do not
   * correspond to build rule arguments specified by the user. Instead, they contain internal-only
   * metadata, so they should not be printed when the build rule is reproduced.
   */
  private static final String INTERNAL_PROPERTY_NAME_PREFIX = "buck.";

  /**
   * The name of the property in the JSON produced by {@code buck.py} that identifies the type of
   * the build rule being defined.
   * <p>
   * TODO(mbolin): Change this property name to "buck.type" so that all internal properties start
   * with "buck.".
   */
  private static final String TYPE_PROPERTY_NAME = "type";

  /** Properties that should be listed last in the declaration of a build rule. */
  private static final ImmutableSet<String> LAST_PROPERTIES = ImmutableSet.of("deps", "visibility");

  protected AuditRulesCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  AuditRulesOptions createOptions(BuckConfig buckConfig) {
    return new AuditRulesOptions(buckConfig);
  }

  @Override
  String getUsageIntro() {
    return "List build rule definitions resulting from expanding macros.";
  }

  /** Prints the expanded build rules from the specified build files to the console. */
  @Override
  int runCommandWithOptionsInternal(AuditRulesOptions options)
      throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = getProjectFilesystem();

    ProjectBuildFileParserFactory factory = new DefaultProjectBuildFileParserFactory(
        projectFilesystem,
        new ParserConfig(options.getBuckConfig()),
        // TODO(simons): When we land dynamic loading, this MUST change.
        getRepository().getAllDescriptions());
    try (ProjectBuildFileParser parser = factory.createParser(
        console,
        environment,
        getBuckEventBus())) {
      PrintStream out = console.getStdOut();
      for (String pathToBuildFile : options.getArguments()) {
        // Print a comment with the path to the build file.
        out.printf("# %s\n\n", pathToBuildFile);

        // Resolve the path specified by the user.
        Path path = Paths.get(pathToBuildFile);
        if (!path.isAbsolute()) {
          Path root = projectFilesystem.getRootPath();
          path = root.resolve(path);
        }

        // Parse the rules from the build file.
        List<Map<String, Object>> rawRules;
        try {
          rawRules = parser.getAll(path);
        } catch (BuildFileParseException e) {
          throw new HumanReadableException(e);
        }

        // Format and print the rules from the raw data, filtered by type.
        final ImmutableSet<String> types = options.getTypes();
        Predicate<String> includeType = new Predicate<String>() {
          @Override
          public boolean apply(String type) {
            return types.isEmpty() || types.contains(type);
          }
        };
        printRulesToStdout(rawRules, includeType);
      }
    } catch (BuildFileParseException e) {
      throw new HumanReadableException("Unable to create parser");
    }

    return 0;
  }

  private void printRulesToStdout(List<Map<String, Object>> rawRules,
      Predicate<String> includeType) {
    PrintStream out = console.getStdOut();

    for (Map<String, Object> rawRule : rawRules) {
      String type = (String) rawRule.get(TYPE_PROPERTY_NAME);
      if (!includeType.apply(type)) {
        continue;
      }

      out.printf("%s(\n", type);

      // The properties in the order they should be displayed for this rule.
      LinkedHashSet<String> properties = Sets.newLinkedHashSet();

      // Always display the "name" property first.
      properties.add("name");

      // Add the properties specific to the rule.
      SortedSet<String> customProperties = Sets.newTreeSet();
      for (String key : rawRule.keySet()) {
        // Ignore keys that start with "buck.".
        if (!(key.equals(TYPE_PROPERTY_NAME) ||
            key.startsWith(INTERNAL_PROPERTY_NAME_PREFIX) ||
            LAST_PROPERTIES.contains(key))) {
          customProperties.add(key);
        }
      }
      properties.addAll(customProperties);

      // Add common properties that should be displayed last.
      properties.addAll(LAST_PROPERTIES);

      // Write out the properties and their corresponding values.
      for (String property : properties) {
        String displayValue = createDisplayString(INDENT, rawRule.get(property));
        out.printf("%s%s = %s,\n", INDENT, property, displayValue);
      }

      // Close the rule definition.
      out.printf(")\n\n");
    }
  }

  /**
   * @param value in a Map returned by {@link ProjectBuildFileParser#getAll(Path)}.
   * @return a string that represents the Python equivalent of the value.
   */
  @VisibleForTesting
  static String createDisplayString(@Nullable Object value) {
    return createDisplayString("", value);
  }

  static String createDisplayString(String indent, @Nullable Object value) {
    if (value == null) {
      return "None";
    } else if (value instanceof Boolean) {
      return MoreStrings.capitalize(value.toString());
    } else if (value instanceof String) {
      return Escaper.escapeAsPythonString(value.toString());
    } else if (value instanceof Number) {
      return value.toString();
    } else if (value instanceof List) {
      StringBuilder out = new StringBuilder("[\n");

      String indentPlus1 = indent + INDENT;
      for (Object item : (List<?>) value) {
        out.append(indentPlus1)
            .append(createDisplayString(indentPlus1, item))
            .append(",\n");
      }

      out.append(indent).append("]");
      return out.toString();
    } else if (value instanceof Map) {
      StringBuilder out = new StringBuilder("{\n");

      String indentPlus1 = indent + INDENT;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        out.append(indentPlus1)
            .append(createDisplayString(indentPlus1, entry.getKey()))
            .append(": ")
            .append(createDisplayString(indentPlus1, entry.getValue()))
            .append(",\n");
      }

      out.append(indent).append("}");
      return out.toString();
    } else {
      throw new IllegalStateException();
    }
  }
}
