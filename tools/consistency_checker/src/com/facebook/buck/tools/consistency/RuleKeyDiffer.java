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

import com.facebook.buck.log.thrift.rulekeys.Value;
import com.facebook.buck.tools.consistency.DifferState.DiffResult;
import com.facebook.buck.tools.consistency.DifferState.MaxDifferencesException;
import com.facebook.buck.tools.consistency.RuleKeyDiffPrinter.TargetScope;
import com.facebook.buck.tools.consistency.RuleKeyDiffPrinter.TargetScope.PropertyScope;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.ParsedRuleKeyFile;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.RuleKeyNode;
import com.google.common.collect.Sets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Traverses a graph of rule keys and prints out the differences between them. */
public class RuleKeyDiffer {

  private final RuleKeyDiffPrinter printer;

  /**
   * Signals that there was an issue while traversing one of the graphs. This generally means that
   * the graph is malformed
   */
  public class GraphTraversalException extends Exception {
    GraphTraversalException(String message, Object... formatObjects) {
      super(String.format(message, formatObjects));
    }

    GraphTraversalException(Throwable e, String message, Object... formatObjects) {
      super(String.format(message, formatObjects), e);
    }
  }

  public RuleKeyDiffer(RuleKeyDiffPrinter printer) {
    this.printer = printer;
  }

  /**
   * Prints the differences between two files' rule key graphs
   *
   * @param originalFile The parsed original file
   * @param newFile The new file to use
   * @throws MaxDifferencesException Thrown if the maximum number of differences has been found
   * @returns true if differences were found, false otherwise
   */
  public DiffResult printDiff(ParsedRuleKeyFile originalFile, ParsedRuleKeyFile newFile)
      throws MaxDifferencesException, GraphTraversalException {
    if (!originalFile.rootNodes.keySet().equals(newFile.rootNodes.keySet())) {
      String originalTargets =
          originalFile.rootNodes.keySet().stream().collect(Collectors.joining(","));
      String newTargets = newFile.rootNodes.keySet().stream().collect(Collectors.joining(","));
      throw new GraphTraversalException(
          "Root nodes in %s do not match root nodes in %s. %s vs %s",
          originalFile.filename, newFile.filename, originalTargets, newTargets);
    }
    int visitId = 1;
    for (Map.Entry<String, RuleKeyNode> entry : originalFile.rootNodes.entrySet()) {
      printDiff(
          originalFile, entry.getValue(), newFile, newFile.rootNodes.get(entry.getKey()), visitId);
      visitId++;
    }
    return printer.hasChanges();
  }

  private void printDiff(
      ParsedRuleKeyFile originalFile,
      RuleKeyNode originalRuleKey,
      ParsedRuleKeyFile newFile,
      RuleKeyNode newRuleKey,
      int visitId)
      throws MaxDifferencesException, GraphTraversalException {
    if (originalRuleKey.ruleKey.key.equals(newRuleKey.ruleKey.key)) {
      return;
    }

    if (originalRuleKey.lastVisitId == visitId && newRuleKey.lastVisitId == visitId) {
      return;
    }

    originalRuleKey.lastVisitId = visitId;
    newRuleKey.lastVisitId = visitId;

    Set<String> originalRuleKeyProperties = originalRuleKey.ruleKey.values.keySet();
    Set<String> newRuleKeyProperties = newRuleKey.ruleKey.values.keySet();
    Set<String> onlyInOriginalKey =
        Sets.difference(originalRuleKeyProperties, newRuleKeyProperties);
    Set<String> onlyInNewKey = Sets.difference(newRuleKeyProperties, originalRuleKeyProperties);
    Set<String> inBoth = Sets.intersection(originalRuleKeyProperties, newRuleKeyProperties);

    String target = RuleKeyDiffPrinter.getRuleKeyName(originalFile, originalRuleKey.ruleKey);

    try (TargetScope targetScope =
        printer.addTarget(target, originalRuleKey.ruleKey.key, newRuleKey.ruleKey.key)) {
      try (PropertyScope rootProperty = targetScope.addProperty("")) {
        for (String key : onlyInOriginalKey) {
          try (PropertyScope propertyScope = rootProperty.addNestedProperty(key)) {
            propertyScope.removed(originalFile, originalRuleKey.ruleKey.values.get(key));
          }
        }
        for (String key : onlyInNewKey) {
          try (PropertyScope propertyScope = rootProperty.addNestedProperty(key)) {
            propertyScope.added(newFile, newRuleKey.ruleKey.values.get(key));
          }
        }
        for (String key : inBoth) {
          try (PropertyScope propertyScope = rootProperty.addNestedProperty(key)) {
            printDiff(
                propertyScope,
                originalFile,
                originalRuleKey.ruleKey.values.get(key),
                newFile,
                newRuleKey.ruleKey.values.get(key));
          }
        }
      }
    } catch (MaxDifferencesException e) {
      throw e;
    } catch (Exception e) {
      throw new GraphTraversalException(e, "Unexpected error while traversing rule key graph");
    }
  }

  private void printDiff(
      PropertyScope propertiesSoFar,
      ParsedRuleKeyFile originalFile,
      Value originalValue,
      ParsedRuleKeyFile newFile,
      Value newValue)
      throws MaxDifferencesException, GraphTraversalException {
    if (originalValue.equals(newValue)) {
      return;
    }
    if (originalValue.getSetField() != newValue.getSetField()) {
      propertiesSoFar.changed(originalFile, originalValue, newFile, newValue);
      return;
    }
    try {
      switch (originalValue.getSetField()) {
        case STRING_VALUE:
        case NUMBER_VALUE:
        case BOOL_VALUE:
        case NULL_VALUE:
        case HASHED_PATH:
        case PATH:
        case SHA1_HASH:
        case PATTERN:
        case BYTE_ARRAY:
        case ARCHIVE_MEMBER_PATH:
        case BUILD_RULE_TYPE:
        case BUILD_TARGET:
        case TARGET_PATH:
          propertiesSoFar.changed(originalFile, originalValue, newFile, newValue);
          break;
        case CONTAINER_MAP:
          printContainerMapDiff(propertiesSoFar, originalFile, originalValue, newFile, newValue);
          break;
        case CONTAINER_LIST:
          printContainerListDiff(propertiesSoFar, originalFile, originalValue, newFile, newValue);
          break;
        case WRAPPER:
          printWrapperDiff(propertiesSoFar, originalFile, originalValue, newFile, newValue);
          break;
        case RULE_KEY_HASH:
          printRuleKeyHashDiff(propertiesSoFar, originalFile, originalValue, newFile, newValue);
          break;
        case KEY:
          break;
      }
    } catch (MaxDifferencesException e) {
      throw e;
    } catch (Exception e) {
      throw new GraphTraversalException(
          e, "Unexpected error while traversing rule key's properties");
    }
  }

  private void printContainerMapDiff(
      PropertyScope propertiesSoFar,
      ParsedRuleKeyFile originalFile,
      Value originalValue,
      ParsedRuleKeyFile newFile,
      Value newValue)
      throws Exception {
    Set<String> originalKeyProperties = originalValue.getContainerMap().keySet();
    Set<String> newKeyProperties = newValue.getContainerMap().keySet();
    Set<String> onlyInOrigKey = Sets.difference(originalKeyProperties, newKeyProperties);
    Set<String> onlyInNewKey = Sets.difference(newKeyProperties, originalKeyProperties);
    Set<String> inBoth = Sets.intersection(originalKeyProperties, newKeyProperties);
    for (String k : onlyInOrigKey) {
      try (PropertyScope propertyScope = propertiesSoFar.addNestedProperty(k)) {
        propertyScope.removed(originalFile, originalValue.getContainerMap().get(k));
      }
    }
    for (String k : onlyInNewKey) {
      try (PropertyScope propertyScope = propertiesSoFar.addNestedProperty(k)) {
        propertyScope.added(newFile, newValue.getContainerMap().get(k));
      }
    }
    for (String k : inBoth) {
      try (PropertyScope propertyScope = propertiesSoFar.addNestedProperty(k)) {
        printDiff(
            propertyScope,
            originalFile,
            originalValue.getContainerMap().get(k),
            newFile,
            newValue.getContainerMap().get(k));
      }
    }
  }

  private void printContainerListDiff(
      PropertyScope propertiesSoFar,
      ParsedRuleKeyFile originalFile,
      Value originalValue,
      ParsedRuleKeyFile newFile,
      Value newValue)
      throws Exception {
    int commonListLength =
        Math.min(originalValue.getContainerList().size(), newValue.getContainerList().size());
    for (int i = 0; i < commonListLength; i++) {
      try (PropertyScope propertyScope = propertiesSoFar.addNestedProperty(String.valueOf(i))) {
        printDiff(
            propertyScope,
            originalFile,
            originalValue.getContainerList().get(i),
            newFile,
            newValue.getContainerList().get(i));
      }
    }
    for (int i = commonListLength; i < originalValue.getContainerList().size(); i++) {
      try (PropertyScope propertyScope = propertiesSoFar.addNestedProperty(String.valueOf(i))) {
        propertyScope.removed(originalFile, originalValue.getContainerList().get(i));
      }
    }
    for (int i = commonListLength; i < newValue.getContainerList().size(); i++) {
      try (PropertyScope propertyScope = propertiesSoFar.addNestedProperty(String.valueOf(i))) {
        propertyScope.added(newFile, newValue.getContainerList().get(i));
      }
    }
  }

  private void printRuleKeyHashDiff(
      PropertyScope propertiesSoFar,
      ParsedRuleKeyFile originalFile,
      Value originalValue,
      ParsedRuleKeyFile newFile,
      Value newValue)
      throws MaxDifferencesException, GraphTraversalException {
    RuleKeyNode nextOriginalRuleKey = originalFile.rules.get(originalValue.getRuleKeyHash().sha1);
    RuleKeyNode nextNewRuleKey = newFile.rules.get(newValue.getRuleKeyHash().sha1);

    String originalName =
        RuleKeyDiffPrinter.getRuleKeyName(originalFile, nextOriginalRuleKey.ruleKey);
    String newName = RuleKeyDiffPrinter.getRuleKeyName(newFile, nextNewRuleKey.ruleKey);
    if (originalName.equals(newName)) {
      // Only recurse if we've got the same name, otherwise we can already tell
      // where the divergence happened
      propertiesSoFar.recordEmptyChange();
      printDiff(originalFile, nextOriginalRuleKey, newFile, nextNewRuleKey, 1);
    } else {
      // If the rule keys are significantly different, just print their names out
      propertiesSoFar.changed(originalFile, originalValue, newFile, newValue);
    }
  }

  private void printWrapperDiff(
      PropertyScope propertiesSoFar,
      ParsedRuleKeyFile originalFile,
      Value originalValue,
      ParsedRuleKeyFile newFile,
      Value newValue)
      throws Exception {
    if (!originalValue.getWrapper().type.equals(newValue.getWrapper().type)) {
      propertiesSoFar.changed(originalFile, originalValue, newFile, newValue);
    } else {
      try (PropertyScope propertyScope =
          propertiesSoFar.addNestedProperty(originalValue.getWrapper().type)) {
        printDiff(
            propertyScope,
            originalFile,
            originalValue.getWrapper().value,
            newFile,
            newValue.getWrapper().value);
      }
    }
  }
}
