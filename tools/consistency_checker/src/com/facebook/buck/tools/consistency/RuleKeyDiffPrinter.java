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
import com.facebook.buck.log.thrift.rulekeys.RuleKeyHash;
import com.facebook.buck.log.thrift.rulekeys.Value;
import com.facebook.buck.tools.consistency.DifferState.DiffResult;
import com.facebook.buck.tools.consistency.DifferState.MaxDifferencesException;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.ParsedRuleKeyFile;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.RuleKeyNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Prints out differences found between rule keys and their properties.
 *
 * <p>Normal usage is to instantiate a printer, call {@link #addTarget(String)} when encountering a
 * new RuleKey, then calling {@link #addProperty(String)} on that target scope when examining
 * properties of a RuleKey
 */
public class RuleKeyDiffPrinter {
  private final DiffPrinter diffPrinter;
  private final DifferState differState;

  /**
   * The scope for printing information about a target. Helps ensure that target information is
   * always printed if any differences are found within the target
   */
  public class TargetScope implements AutoCloseable {

    private final String newKey;
    private final String origKey;
    private final String target;
    private List<String> pathComponents = new ArrayList<>();
    private boolean printedTarget = false;

    private TargetScope(String target, String originalKey, String newKey) {
      this.target = target;
      this.origKey = originalKey;
      this.newKey = newKey;
    }

    @Override
    public void close() {}

    /** Prints out the target name and old/new hash */
    private void printHeader() {
      if (!printedTarget) {
        printedTarget = true;
        diffPrinter.printHeader(target, String.format(" (%s vs %s)", origKey, newKey));
      }
    }

    /**
     * Adds a new property to the target scope. If any changes are recorded on this object (e.g.
     * with {@link #added(ParsedRuleKeyFile, Value) added}), the details of the target will be
     * printed first.
     *
     * @param propertyName The path to the property to print at the beginning of the line. e.g. '0'
     *     for the first element of an array, or 'target_name' for a property named 'target_name' in
     *     the main rule key map.
     * @return A {@link PropertyScope} object that can record changes in the object.
     */
    public PropertyScope addProperty(String propertyName) {
      return new PropertyScope(propertyName);
    }

    /**
     * A scope representing a single property of a RuleKey, or a nested value of a RuleKey. This is
     * used to print differences between two different {@link Value} objects
     */
    public class PropertyScope implements AutoCloseable {

      private PropertyScope(String name) {
        pathComponents.add(name);
      }

      /** Pops the most recent property off of the stack of nested properties */
      @Override
      public void close() {
        pathComponents.remove(pathComponents.size() - 1);
      }

      /**
       * Creates a nested property scope that is one level deeper. Useful for printing values inside
       * of a map or list
       */
      public PropertyScope addNestedProperty(String newProperty) {
        return new PropertyScope(newProperty);
      }

      /** Make sure we've printed the header and haven't encountered too many differences */
      private void validateAndUpdateState() throws MaxDifferencesException {
        differState.incrementDifferenceCount();
        printHeader();
      }

      private void printAdd(ParsedRuleKeyFile file, Value value) {
        diffPrinter.printAdd(
            String.format(
                "%s: %s", String.join("/", pathComponents), valueAsReadableString(file, value)));
      }

      private void printRemove(ParsedRuleKeyFile file, Value value) {
        diffPrinter.printRemove(
            String.format(
                "%s: %s", String.join("/", pathComponents), valueAsReadableString(file, value)));
      }

      /**
       * Prints that a property was removed from the original rule key in the new one
       *
       * @param file The file object that has all of the rule keys from the original log file
       * @param value The value that was removed
       * @throws MaxDifferencesException Thrown if the maximum number of differences was found
       *     before this removal was printed
       */
      public void removed(ParsedRuleKeyFile file, Value value) throws MaxDifferencesException {
        validateAndUpdateState();
        printRemove(file, value);
      }

      /**
       * Prints that a property was added to the new rule key that wasn't present in the original
       * one
       *
       * @param file The file object that has all of the rule keys from the new log file
       * @param value The value that was added
       * @throws MaxDifferencesException Thrown if the maximum number of differences was found
       *     before this addition was printed
       */
      public void added(ParsedRuleKeyFile file, Value value) throws MaxDifferencesException {
        validateAndUpdateState();
        printAdd(file, value);
      }

      /**
       * Prints that there was a difference between two values that are at the same path. e.g. if
       * two instances of a rule key have different files at index 0 of their sources list, the
       * filenames would be printed out here.
       *
       * @param originalFile The file object that has all of the rule keys from the original log
       *     file
       * @param originalValue The value from the original rule key
       * @param newFile The file object that has all of the rule keys from the new log file
       * @param newValue The value from the new rule key
       * @throws MaxDifferencesException Thrown if the maximum number of differences was found
       *     before this change was printed
       */
      public void changed(
          ParsedRuleKeyFile originalFile,
          Value originalValue,
          ParsedRuleKeyFile newFile,
          Value newValue)
          throws MaxDifferencesException {
        validateAndUpdateState();
        printRemove(originalFile, originalValue);
        printAdd(newFile, newValue);
      }

      /**
       * Record a change that should not have an add/remove/chage line printed. This ensures that we
       * count the difference and print the header, but don't force rule key differences to be
       * printed right before we recurse
       */
      public void recordEmptyChange() throws MaxDifferencesException {
        differState.incrementDifferenceCount();
        printHeader();
      }
    }
  }

  /**
   * Creates an instance of {@link RuleKeyDiffPrinter}
   *
   * @param diffPrinter An object that prints additions/removals
   * @param differState The state of the actual diffing
   */
  public RuleKeyDiffPrinter(DiffPrinter diffPrinter, DifferState differState) {
    this.diffPrinter = diffPrinter;
    this.differState = differState;
  }

  /**
   * Determines whether any changes have been printed
   *
   * @return whether any changes have been printed
   */
  public DiffResult hasChanges() {
    return differState.hasChanges();
  }

  /**
   * Adds a new {@link TargetScope} to this printer.
   *
   * @param target The target name
   * @param oldKey The old rule key hash
   * @param newKey The new rule key hash
   */
  public TargetScope addTarget(String target, String oldKey, String newKey) {
    return new TargetScope(target, oldKey, newKey);
  }

  /**
   * Gets a string representation of a Value to use when printing diffs
   *
   * @param file The file that this value came from. This is used primarily for looking up readable
   *     names should {@code value} be a {@link RuleKeyHash} type.
   * @param value The value to convert to a string
   * @return A string suitable for printing in a diff
   */
  public static String valueAsReadableString(ParsedRuleKeyFile file, Value value) {
    switch (value.getSetField()) {
      case STRING_VALUE:
        return value.getStringValue();
      case NUMBER_VALUE:
        return String.valueOf(value.getNumberValue());
      case BOOL_VALUE:
        return String.valueOf(value.getBoolValue());
      case NULL_VALUE:
        return "null";
      case HASHED_PATH:
        return String.format(
            "Path: %s, hash: %s", value.getHashedPath().path, value.getHashedPath().hash);
      case PATH:
        return String.format("Path: %s", value.getPath().path);
      case SHA1_HASH:
        return String.format("Sha1: %s", value.getSha1Hash().sha1);
      case PATTERN:
        return String.format("Regex Pattern: %s", value.getPattern().pattern);
      case BYTE_ARRAY:
        return String.format("Byte array length: %s", value.getByteArray().length);
      case CONTAINER_MAP:
        return String.format("Map: Length: %s", value.getContainerMap().size());
      case CONTAINER_LIST:
        return String.format("List: Length: %s", value.getContainerList().size());
      case RULE_KEY_HASH:
        RuleKeyNode rule = file.rules.get(value.getRuleKeyHash().sha1);
        if (rule == null) {
          return String.format("RuleKey: %s", value.getRuleKeyHash().sha1);
        } else {
          return String.format(
              "RuleKey(%s) %s", value.getRuleKeyHash().sha1, getRuleKeyName(file, rule.ruleKey));
        }
      case ARCHIVE_MEMBER_PATH:
        return String.format(
            "ArchiveMemberPath: %s!%s, hash: %s",
            value.getArchiveMemberPath().archivePath,
            value.getArchiveMemberPath().memberPath,
            value.getArchiveMemberPath().hash);
      case BUILD_RULE_TYPE:
        return String.format("BuildRuleType: %s", value.getBuildRuleType().type);
      case WRAPPER:
        return String.format(
            "Wrapper: %s/%s",
            value.getWrapper().type, valueAsReadableString(file, value.getWrapper().value));
      case BUILD_TARGET:
        return String.format("BuildTarget: %s", value.getBuildTarget().name);
      case TARGET_PATH:
        return String.format("TargetPath: %s", value.getTargetPath().path);
      case KEY:
        break;
    }
    return value.getSetField().getFieldName();
  }

  /**
   * Get a useful display name for the given rule key. This is done because sometimes the name is
   * not present for a rule key, and the only description is really available in one of its
   * properties, like the 'arg' property
   *
   * @param file The file that this rule key came from. This is used primarily for looking up
   *     readable names should identifying properties be a {@link RuleKeyHash} type.
   * @param ruleKey The rule key to get a name for
   * @return An appropriate rule key name, or "UNKNOWN NAME" if no name could be determined
   */
  public static String getRuleKeyName(ParsedRuleKeyFile file, FullRuleKey ruleKey) {

    String target = ruleKey.name;
    if (target != null && !target.isEmpty()) {
      return target;
    }
    Value argName = ruleKey.values.get("arg");
    if (argName != null) {
      return String.format("argument: %s", getRuleKeyName(file, argName));
    }
    return "UNKNOWN NAME";
  }

  private static String getRuleKeyName(ParsedRuleKeyFile file, Value value) {
    switch (value.getSetField()) {
      case STRING_VALUE:
        return value.getStringValue();
      case NUMBER_VALUE:
        return Double.toString(value.getNumberValue());
      case BOOL_VALUE:
        return Boolean.toString(value.getBoolValue());
      case NULL_VALUE:
        return "null";
      case HASHED_PATH:
        return value.getHashedPath().path;
      case PATH:
        return value.getPath().path;
      case SHA1_HASH:
        return value.getSha1Hash().sha1;
      case PATTERN:
        return value.getPattern().pattern;
      case BYTE_ARRAY:
        return String.format("ByteArray, length %s", value.getByteArray().length);
      case CONTAINER_MAP:
        return value
            .getContainerMap()
            .entrySet()
            .stream()
            .map(
                entry ->
                    String.format("%s: %s", entry.getKey(), getRuleKeyName(file, entry.getValue())))
            .collect(Collectors.joining(", "));
      case CONTAINER_LIST:
        return value
            .getContainerList()
            .stream()
            .map(v -> getRuleKeyName(file, v))
            .collect(Collectors.joining(", "));
      case RULE_KEY_HASH:
        RuleKeyNode found = file.rules.get(value.getRuleKeyHash().sha1);
        if (found != null) {
          return getRuleKeyName(file, found.ruleKey);
        }
        return "UNKNOWN RULE KEY";
      case ARCHIVE_MEMBER_PATH:
        return String.format(
            "%s!%s",
            value.getArchiveMemberPath().archivePath, value.getArchiveMemberPath().memberPath);
      case BUILD_RULE_TYPE:
        return value.getBuildRuleType().type;
      case WRAPPER:
        return getRuleKeyName(file, value.getWrapper().value);
      case BUILD_TARGET:
        return value.getBuildTarget().name;
      case TARGET_PATH:
        return value.getTargetPath().path;
    }
    return "UNKNOWN NAME";
  }
}
