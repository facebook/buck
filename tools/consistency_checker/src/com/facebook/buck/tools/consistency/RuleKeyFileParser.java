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
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
    public boolean visited = false;

    public RuleKeyNode(FullRuleKey ruleKey) {
      this.ruleKey = ruleKey;
    }
  }

  /** A parsed rule key file with details of the parse, and all rules contained in the file. */
  static class ParsedRuleKeyFile {

    public final Path filename;
    public final RuleKeyNode rootNode;
    public final Map<String, RuleKeyNode> rules;
    public final Duration parseTime;

    public ParsedRuleKeyFile(
        Path filename, RuleKeyNode rootNode, Map<String, RuleKeyNode> rules, Duration parseTime) {
      this.filename = filename;
      this.rootNode = rootNode;
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
   * @param targetName The name of the target that should be found
   * @return A {@link ParsedRuleKeyFile} object that all deserialized rules, and the rule key hash
   *     of the specified target
   * @throws ParseException If an IO or serialization error occurs, or if the target could not be
   *     found in the file
   */
  public ParsedRuleKeyFile parseFile(Path filename, String targetName) throws ParseException {
    long startNanos = System.nanoTime();
    int initialSize;
    try {
      initialSize = Math.toIntExact(filename.toFile().length() / THRIFT_STRUCT_SIZE);
    } catch (ArithmeticException e) {
      throw new ParseException(
          e, "File size is too large (>2.1 billion objects would be deserialized");
    }

    final ImmutableMap.Builder<String, RuleKeyNode> rules = ImmutableMap.builder();
    // Made into an array so we can mutate from the inside of the visitor
    final AtomicReference<RuleKeyNode> rootNode = new AtomicReference<>(null);

    reader.readFile(
        filename,
        ruleKey -> {
          RuleKeyNode newNode = new RuleKeyNode(ruleKey);
          if ("DEFAULT".equals(ruleKey.type) && targetName.equals(ruleKey.name)) {
            rootNode.set(newNode);
          }
          rules.put(ruleKey.key, newNode);
          return false;
        });

    if (rootNode.get() == null) {
      throw new ParseException("Could not find %s in %s", targetName, filename);
    }
    Duration runtime = Duration.ofNanos(System.nanoTime() - startNanos);
    return new ParsedRuleKeyFile(filename, rootNode.get(), rules.build(), runtime);
  }
}
