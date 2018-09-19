/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.tools.consistency;

import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** A class that can parse out length prefixed, thrift compact encoded rule keys. */
public class RuleKeyFileParser {

  private final RuleKeyLogFileReader reader;

  public RuleKeyFileParser(RuleKeyLogFileReader reader) {
    this.reader = reader;
  }

  /**
   * A node in the set of all rule keys. When parsing, it tracks whether the node has been visited
   * yet or not.
   */
  static class RuleKeyNode {

    public final FullRuleKey ruleKey;
    public int lastVisitId;

    public RuleKeyNode(FullRuleKey ruleKey) {
      this.ruleKey = ruleKey;
    }

    public String toString() {
      return ruleKey.toString();
    }
  }

  /** A parsed rule key file with details of the parse, and all rules contained in the file. */
  static class ParsedRuleKeyFile {

    public final Path filename;
    public final ImmutableMap<String, RuleKeyNode> rootNodes;
    public final Map<String, RuleKeyNode> rules;
    public final Duration parseTime;

    public ParsedRuleKeyFile(
        Path filename,
        ImmutableMap<String, RuleKeyNode> rootNodes,
        Map<String, RuleKeyNode> rules,
        Duration parseTime) {
      this.filename = filename;
      this.rootNodes = rootNodes;
      this.rules = rules;
      this.parseTime = parseTime;
    }
  }

  /** Arbitrary estimate of an average rule key size when serialized */
  public final int THRIFT_STRUCT_SIZE = 300;

  /**
   * Parse a thrift compact serialized file
   *
   * @param filename The name of the file
   * @param targetNames The name of the targets that should be found
   * @return A {@link ParsedRuleKeyFile} object that all deserialized rules, and the rule key hash
   *     of the specified target
   * @throws ParseException If an IO or serialization error occurs, or if the target could not be
   *     found in the file
   */
  public ParsedRuleKeyFile parseFile(Path filename, ImmutableSet<String> targetNames)
      throws ParseException {
    // If //foo/bar/... is passed in, we want to find all targets that start with
    // //foo/bar, and that are of the right type, and add them as root nodes
    ImmutableList<String> recursiveTargetPrefixes =
        targetNames
            .stream()
            .filter(name -> name.endsWith("/...") || name.endsWith(":"))
            .map(
                name -> {
                  int idx = name.lastIndexOf("/...");
                  if (idx != -1) {
                    return name.substring(0, idx);
                  } else {
                    return name;
                  }
                })
            .collect(ImmutableList.toImmutableList());
    long startNanos = System.nanoTime();
    int initialSize;
    try {
      initialSize = Math.toIntExact(filename.toFile().length() / THRIFT_STRUCT_SIZE);
    } catch (ArithmeticException e) {
      throw new ParseException(
          e, filename, "File size is too large (>2.1 billion objects would be deserialized");
    }

    ImmutableMap.Builder<String, RuleKeyNode> rootNodesBuilder = ImmutableMap.builder();
    Map<String, RuleKeyNode> rules = new HashMap<>();

    try {
      reader.readFile(
          filename,
          ruleKey -> {
            RuleKeyNode newNode = new RuleKeyNode(ruleKey);
            if ("DEFAULT".equals(ruleKey.type)) {
              // If either a specific rule is present, or if the target starts with one of the
              // prefixes
              if (targetNames.contains(ruleKey.name)
                  || recursiveTargetPrefixes
                      .stream()
                      .filter(prefix -> ruleKey.name.startsWith(prefix))
                      .findFirst()
                      .isPresent()) {
                rootNodesBuilder.put(ruleKey.name, newNode);
              }
            }
            RuleKeyNode oldValue = rules.put(ruleKey.key, newNode);
            if (oldValue != null && !oldValue.ruleKey.equals(newNode.ruleKey)) {
              throw new RuntimeException(
                  new ParseException(
                      filename,
                      "Found two rules with the same key, but different values. Key: %s, first value: "
                          + "%s, second value: %s",
                      ruleKey.key,
                      oldValue.ruleKey,
                      newNode.ruleKey));
            }
            return false;
          });
    } catch (RuntimeException e) {
      if (e.getCause() instanceof ParseException) {
        throw (ParseException) e.getCause();
      }
    }

    ImmutableMap<String, RuleKeyNode> rootNodes = rootNodesBuilder.build();
    for (String targetName : targetNames) {
      if (!targetName.endsWith("/...")
          && !targetName.endsWith(":")
          && !rootNodes.containsKey(targetName)) {
        throw new ParseException(filename, "Could not find %s in %s", targetName, filename);
      }
    }
    Duration runtime = Duration.ofNanos(System.nanoTime() - startNanos);
    return new ParsedRuleKeyFile(filename, rootNodes, rules, runtime);
  }
}
