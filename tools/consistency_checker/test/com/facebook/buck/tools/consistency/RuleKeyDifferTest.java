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

import com.facebook.buck.log.thrift.rulekeys.ArchiveMemberPath;
import com.facebook.buck.log.thrift.rulekeys.BuildRuleType;
import com.facebook.buck.log.thrift.rulekeys.BuildTarget;
import com.facebook.buck.log.thrift.rulekeys.ByteArray;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.log.thrift.rulekeys.HashedPath;
import com.facebook.buck.log.thrift.rulekeys.NonHashedPath;
import com.facebook.buck.log.thrift.rulekeys.Pattern;
import com.facebook.buck.log.thrift.rulekeys.RuleKeyHash;
import com.facebook.buck.log.thrift.rulekeys.Sha1;
import com.facebook.buck.log.thrift.rulekeys.TargetPath;
import com.facebook.buck.log.thrift.rulekeys.Value;
import com.facebook.buck.log.thrift.rulekeys.Wrapper;
import com.facebook.buck.tools.consistency.DifferState.MaxDifferencesException;
import com.facebook.buck.tools.consistency.RuleKeyDiffPrinter.TargetScope;
import com.facebook.buck.tools.consistency.RuleKeyDiffPrinter.TargetScope.PropertyScope;
import com.facebook.buck.tools.consistency.RuleKeyDiffer.GraphTraversalException;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.ParsedRuleKeyFile;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.RuleKeyNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleKeyDifferTest {

  @Rule public ExpectedException expectedThrownException = ExpectedException.none();

  private TestPrintStream stream = TestPrintStream.create();
  private RuleKeyDiffPrinter printer;
  private DiffPrinter diffPrinter;
  private DifferState differState;
  private RuleKeyDiffer differ;

  private TestPrintStream expectedStream = TestPrintStream.create();
  private DiffPrinter expectedDiffPrinter;
  private DifferState expectedDifferState;
  private RuleKeyDiffPrinter expectedPrinter;

  @Before
  public void setUp() {
    differState = new DifferState(DifferState.INFINITE_DIFFERENCES);
    diffPrinter = new DiffPrinter(stream, false);
    printer = new RuleKeyDiffPrinter(diffPrinter, differState);

    expectedDifferState = new DifferState(DifferState.INFINITE_DIFFERENCES);
    expectedDiffPrinter = new DiffPrinter(expectedStream, false);
    expectedPrinter = new RuleKeyDiffPrinter(expectedDiffPrinter, expectedDifferState);

    differ = new RuleKeyDiffer(printer);
  }

  private Map<String, Value> generateSampleValues(long id, long valueSeed) {
    LinkedHashMap<String, Value> ret = new LinkedHashMap<>();
    ret.put(String.format("string_value_%s", id), Value.stringValue(Long.toString(valueSeed)));
    ret.put(String.format("number_value_%s", id), Value.numberValue(valueSeed));
    ret.put(String.format("bool_value_%s", id), Value.boolValue(valueSeed % 2 == 0));
    ret.put(
        String.format("hashed_path_%s", id),
        Value.hashedPath(
            new HashedPath(
                String.format("hashed_path_%s", valueSeed), String.format("hash_%s", valueSeed))));
    ret.put(
        String.format("path_%s", id),
        Value.path(new NonHashedPath(String.format("path_%s", valueSeed))));
    ret.put(
        String.format("sha1_hash_%s", id),
        Value.sha1Hash(new Sha1(String.format("hash_%s", valueSeed))));
    ret.put(
        String.format("pattern_%s", id),
        Value.pattern(new Pattern(String.format(".*pattern_%s", valueSeed))));
    ret.put(String.format("byte_array_%s", id), Value.byteArray(new ByteArray(valueSeed)));
    ret.put(
        String.format("archive_member_path_%s", id),
        Value.archiveMemberPath(
            new ArchiveMemberPath(
                String.format("archive_path_%s", valueSeed),
                String.format("member_path_%s", valueSeed),
                String.format("hash_%s", valueSeed))));
    ret.put(
        String.format("build_rule_type_%s", id),
        Value.buildRuleType(new BuildRuleType(String.format("rule_type_%s", valueSeed))));
    ret.put(
        String.format("build_target_%s", id),
        Value.buildTarget(new BuildTarget(String.format("//:rule_%s", valueSeed))));
    ret.put(
        String.format("target_path_%s", id),
        Value.targetPath(new TargetPath(String.format("//path_%s", valueSeed))));
    return ret;
  }

  private List<String> generateExpectedStrings(
      Collection<String> keys, ParsedRuleKeyFile originalFile, ParsedRuleKeyFile newFile)
      throws MaxDifferencesException {
    FullRuleKey originalRuleKey = originalFile.rootNodes.get("//:target1").ruleKey;
    FullRuleKey newRuleKey = newFile.rootNodes.get("//:target1").ruleKey;
    try (TargetScope ts =
        expectedPrinter.addTarget(originalRuleKey.name, originalRuleKey.key, newRuleKey.key)) {
      for (String key : keys) {
        try (PropertyScope rootScope = ts.addProperty("")) {
          try (PropertyScope ps = rootScope.addNestedProperty(key)) {
            if (originalRuleKey.values.containsKey(key)) {
              ps.removed(originalFile, originalRuleKey.values.get(key));
            }
            if (newRuleKey.values.containsKey(key)) {
              ps.added(newFile, newRuleKey.values.get(key));
            }
          }
        }
      }
    }
    return Arrays.asList(expectedStream.getOutputLines());
  }

  private ParsedRuleKeyFile createParsedFile(
      Path filename, FullRuleKey rootKey, FullRuleKey... otherKeys) {
    RuleKeyNode rootNode = new RuleKeyNode(rootKey);
    return new ParsedRuleKeyFile(
        filename,
        ImmutableMap.of(rootNode.ruleKey.name, rootNode),
        ImmutableMap.<String, RuleKeyNode>builder()
            .put(rootKey.key, rootNode)
            .putAll(
                Arrays.stream(otherKeys).collect(Collectors.toMap(v -> v.key, RuleKeyNode::new)))
            .build(),
        Duration.ofNanos(1));
  }

  @Test
  public void handlesDifferencesForBaseTypes()
      throws MaxDifferencesException, GraphTraversalException {
    Map<String, Value> onlyInOriginal = generateSampleValues(1, 1);
    Map<String, Value> changedInOriginal = generateSampleValues(2, 1);
    Map<String, Value> changedInNew = generateSampleValues(2, 2);
    Map<String, Value> common = generateSampleValues(3, 1);
    Map<String, Value> onlyInNew = generateSampleValues(4, 1);

    LinkedHashMap<String, Value> originalValues = new LinkedHashMap<>();
    originalValues.putAll(onlyInOriginal);
    originalValues.putAll(changedInOriginal);
    originalValues.putAll(common);

    LinkedHashMap<String, Value> newValues = new LinkedHashMap<>();
    newValues.putAll(onlyInNew);
    newValues.putAll(changedInNew);
    newValues.putAll(common);

    FullRuleKey originalRuleKey =
        new FullRuleKey("original_hash", "//:target1", "rule_type", originalValues);

    FullRuleKey newRuleKey = new FullRuleKey("new_hash", "//:target1", "rule_type", newValues);

    ParsedRuleKeyFile originalFile = createParsedFile(Paths.get("file1"), originalRuleKey);
    ParsedRuleKeyFile newFile = createParsedFile(Paths.get("file2"), newRuleKey);

    List<String> expectedLines =
        generateExpectedStrings(
            ImmutableList.<String>builder()
                .addAll(onlyInOriginal.keySet())
                .addAll(onlyInNew.keySet())
                .addAll(changedInOriginal.keySet())
                .build(),
            originalFile,
            newFile);

    differ.printDiff(originalFile, newFile);
    List<String> lines = Arrays.asList(stream.getOutputLines());

    Assert.assertEquals(expectedLines.size(), lines.size());
    Assert.assertEquals(expectedLines, lines);
  }

  @Test
  public void handlesMapDifferences() throws MaxDifferencesException, GraphTraversalException {
    LinkedHashMap<String, Value> originalValues = new LinkedHashMap<>();
    originalValues.put("original_1", Value.stringValue("string1"));
    originalValues.put("different_1", Value.stringValue("string2"));
    originalValues.put("common_1", Value.stringValue("string3"));

    LinkedHashMap<String, Value> newValues = new LinkedHashMap<>();
    newValues.put("new_1", Value.stringValue("string4"));
    newValues.put("different_1", Value.stringValue("string5"));
    newValues.put("common_1", Value.stringValue("string3"));

    FullRuleKey originalRuleKey =
        new FullRuleKey(
            "hash_1",
            "//:target1",
            "rule_type",
            ImmutableMap.of("map_1", Value.containerMap(originalValues)));
    FullRuleKey newRuleKey =
        new FullRuleKey(
            "hash_2",
            "//:target1",
            "rule_type",
            ImmutableMap.of("map_1", Value.containerMap(newValues)));

    ParsedRuleKeyFile originalFile = createParsedFile(Paths.get("file1"), originalRuleKey);
    ParsedRuleKeyFile newFile = createParsedFile(Paths.get("file2"), newRuleKey);

    try (TargetScope ts =
        expectedPrinter.addTarget("//:target1", originalRuleKey.key, newRuleKey.key)) {
      try (PropertyScope rootPropertyScope = ts.addProperty("")) {
        try (PropertyScope mapScope = rootPropertyScope.addNestedProperty("map_1")) {
          Map<String, Value> originalMap = originalRuleKey.values.get("map_1").getContainerMap();
          Map<String, Value> newMap = newRuleKey.values.get("map_1").getContainerMap();

          try (PropertyScope ps = mapScope.addNestedProperty("original_1")) {
            ps.removed(originalFile, originalMap.get("original_1"));
          }
          try (PropertyScope ps = mapScope.addNestedProperty("new_1")) {
            ps.added(newFile, newMap.get("new_1"));
          }
          try (PropertyScope ps = mapScope.addNestedProperty("different_1")) {
            ps.removed(originalFile, originalMap.get("different_1"));
            ps.added(newFile, newMap.get("different_1"));
          }
        }
      }
    }

    List<String> expectedLines = Arrays.asList(expectedStream.getOutputLines());

    differ.printDiff(originalFile, newFile);
    List<String> lines = Arrays.asList(stream.getOutputLines());

    Assert.assertEquals(expectedLines, lines);
  }

  @Test
  public void handlesListDifferences() throws MaxDifferencesException, GraphTraversalException {
    LinkedHashMap<String, Value> originalValues = new LinkedHashMap<>();
    originalValues.put(
        "list_1",
        Value.containerList(
            ImmutableList.of(
                Value.stringValue("common_string_1"), Value.stringValue("original_string_1"))));
    originalValues.put(
        "list_2", Value.containerList(ImmutableList.of(Value.stringValue("common_string_2"))));
    originalValues.put(
        "list_3",
        Value.containerList(
            ImmutableList.of(
                Value.stringValue("common_string_3"), Value.stringValue("original_string_2"))));

    LinkedHashMap<String, Value> newValues = new LinkedHashMap<>();
    newValues.put(
        "list_1",
        Value.containerList(
            ImmutableList.of(
                Value.stringValue("common_string_1"), Value.stringValue("new_string_1"))));
    newValues.put(
        "list_2",
        Value.containerList(
            ImmutableList.of(
                Value.stringValue("common_string_2"), Value.stringValue("new_string_2"))));
    newValues.put(
        "list_3", Value.containerList(ImmutableList.of(Value.stringValue("common_string_3"))));

    FullRuleKey originalRuleKey =
        new FullRuleKey("hash_1", "//:target1", "rule_type", originalValues);

    FullRuleKey newRuleKey = new FullRuleKey("hash_2", "//:target1", "rule_type", newValues);

    ParsedRuleKeyFile originalFile = createParsedFile(Paths.get("file1"), originalRuleKey);
    ParsedRuleKeyFile newFile = createParsedFile(Paths.get("file2"), newRuleKey);

    try (TargetScope ts =
        expectedPrinter.addTarget("//:target1", originalRuleKey.key, newRuleKey.key)) {
      try (PropertyScope rootPropertyScope = ts.addProperty("")) {
        try (PropertyScope listScope = rootPropertyScope.addNestedProperty("list_1")) {
          try (PropertyScope ps = listScope.addNestedProperty("1")) {
            ps.changed(
                originalFile,
                originalRuleKey.values.get("list_1").getContainerList().get(1),
                newFile,
                newRuleKey.values.get("list_1").getContainerList().get(1));
          }
        }
        try (PropertyScope listScope = rootPropertyScope.addNestedProperty("list_2")) {
          try (PropertyScope ps = listScope.addNestedProperty("1")) {
            ps.added(newFile, newRuleKey.values.get("list_2").getContainerList().get(1));
          }
        }
        try (PropertyScope listScope = rootPropertyScope.addNestedProperty("list_3")) {
          try (PropertyScope ps = listScope.addNestedProperty("1")) {
            ps.removed(
                originalFile, originalRuleKey.values.get("list_3").getContainerList().get(1));
          }
        }
      }
    }

    List<String> expectedLines = Arrays.asList(expectedStream.getOutputLines());

    differ.printDiff(originalFile, newFile);
    List<String> lines = Arrays.asList(stream.getOutputLines());

    Assert.assertEquals(expectedLines, lines);
  }

  @Test
  public void handlesRuleKeyHashDiffsIfNameIsSame()
      throws MaxDifferencesException, GraphTraversalException {
    FullRuleKey originalRuleKey =
        new FullRuleKey(
            "hash_1",
            "//:target1",
            "rule_type",
            ImmutableMap.of("rk1", Value.ruleKeyHash(new RuleKeyHash("hash_3"))));
    FullRuleKey newRuleKey =
        new FullRuleKey(
            "hash_2",
            "//:target1",
            "rule_type",
            ImmutableMap.of("rk1", Value.ruleKeyHash(new RuleKeyHash("hash_4"))));
    FullRuleKey originalRuleKey2 =
        new FullRuleKey(
            "hash_3",
            "//:target2",
            "rule_type",
            ImmutableMap.of("s1", Value.stringValue("string_1")));
    FullRuleKey newRuleKey2 =
        new FullRuleKey(
            "hash_4",
            "//:target2",
            "rule_type",
            ImmutableMap.of("s1", Value.stringValue("string_2")));

    ParsedRuleKeyFile originalFile =
        createParsedFile(Paths.get("file1"), originalRuleKey, originalRuleKey2);
    ParsedRuleKeyFile newFile = createParsedFile(Paths.get("file2"), newRuleKey, newRuleKey2);

    try (TargetScope ts =
        expectedPrinter.addTarget("//:target1", originalRuleKey.key, newRuleKey.key)) {
      try (PropertyScope ps = ts.addProperty("")) {
        ps.recordEmptyChange();
      }
    }
    try (TargetScope ts =
        expectedPrinter.addTarget("//:target2", originalRuleKey2.key, newRuleKey2.key)) {
      try (PropertyScope rootPropertyScope = ts.addProperty("")) {
        try (PropertyScope ps = rootPropertyScope.addNestedProperty("s1")) {
          ps.changed(
              originalFile,
              originalRuleKey2.values.get("s1"),
              newFile,
              newRuleKey2.values.get("s1"));
        }
      }
    }

    List<String> expectedLines = Arrays.asList(expectedStream.getOutputLines());

    differ.printDiff(originalFile, newFile);
    List<String> lines = Arrays.asList(stream.getOutputLines());

    Assert.assertEquals(expectedLines, lines);
  }

  @Test
  public void handlesRuleKeyHashDifferencesIfNameIsDifferent()
      throws MaxDifferencesException, GraphTraversalException {
    FullRuleKey originalRuleKey =
        new FullRuleKey(
            "hash_1",
            "//:target1",
            "rule_type",
            ImmutableMap.of("rk1", Value.ruleKeyHash(new RuleKeyHash("hash_3"))));
    FullRuleKey newRuleKey =
        new FullRuleKey(
            "hash_2",
            "//:target1",
            "rule_type",
            ImmutableMap.of("rk1", Value.ruleKeyHash(new RuleKeyHash("hash_4"))));
    FullRuleKey originalRuleKey2 =
        new FullRuleKey(
            "hash_3",
            "//:target2",
            "rule_type",
            ImmutableMap.of("s1", Value.stringValue("string_1")));
    FullRuleKey newRuleKey2 =
        new FullRuleKey(
            "hash_4",
            "//:target3",
            "rule_type",
            ImmutableMap.of("s1", Value.stringValue("string_1")));

    ParsedRuleKeyFile originalFile =
        createParsedFile(Paths.get("file1"), originalRuleKey, originalRuleKey2);
    ParsedRuleKeyFile newFile = createParsedFile(Paths.get("file2"), newRuleKey, newRuleKey2);

    try (TargetScope ts =
        expectedPrinter.addTarget("//:target1", originalRuleKey.key, newRuleKey.key)) {
      try (PropertyScope rootPropertyScope = ts.addProperty("")) {
        try (PropertyScope ps = rootPropertyScope.addNestedProperty("rk1")) {
          ps.changed(
              originalFile,
              originalRuleKey.values.get("rk1"),
              newFile,
              newRuleKey.values.get("rk1"));
        }
      }
    }

    List<String> expectedLines = Arrays.asList(expectedStream.getOutputLines());

    differ.printDiff(originalFile, newFile);
    List<String> lines = Arrays.asList(stream.getOutputLines());

    Assert.assertEquals(expectedLines, lines);
  }

  @Test
  public void handlesWrapperDifferencesIfTypeIsSame()
      throws MaxDifferencesException, GraphTraversalException {
    FullRuleKey originalRuleKey =
        new FullRuleKey(
            "hash_1",
            "//:target1",
            "rule_type",
            ImmutableMap.of(
                "wrapper1", Value.wrapper(new Wrapper("type1", Value.stringValue("string1")))));
    FullRuleKey newRuleKey =
        new FullRuleKey(
            "hash_2",
            "//:target1",
            "rule_type",
            ImmutableMap.of(
                "wrapper1", Value.wrapper(new Wrapper("type1", Value.stringValue("string2")))));

    ParsedRuleKeyFile originalFile = createParsedFile(Paths.get("file1"), originalRuleKey);
    ParsedRuleKeyFile newFile = createParsedFile(Paths.get("file2"), newRuleKey);

    try (TargetScope ts =
        expectedPrinter.addTarget("//:target1", originalRuleKey.key, newRuleKey.key)) {
      try (PropertyScope rootPropertyScope = ts.addProperty("")) {
        try (PropertyScope ps = rootPropertyScope.addNestedProperty("wrapper1")) {
          try (PropertyScope wrapperScope = rootPropertyScope.addNestedProperty("type1")) {
            wrapperScope.changed(
                originalFile,
                originalRuleKey.values.get("wrapper1").getWrapper().value,
                newFile,
                newRuleKey.values.get("wrapper1").getWrapper().value);
          }
        }
      }
    }

    List<String> expectedLines = Arrays.asList(expectedStream.getOutputLines());

    differ.printDiff(originalFile, newFile);
    List<String> lines = Arrays.asList(stream.getOutputLines());

    Assert.assertEquals(expectedLines, lines);
  }

  @Test
  public void handlesWrapperDifferencesIfTypeIsDifferent()
      throws MaxDifferencesException, GraphTraversalException {
    FullRuleKey originalRuleKey =
        new FullRuleKey(
            "hash_1",
            "//:target1",
            "rule_type",
            ImmutableMap.of(
                "wrapper1", Value.wrapper(new Wrapper("type1", Value.stringValue("string")))));
    FullRuleKey newRuleKey =
        new FullRuleKey(
            "hash_2",
            "//:target1",
            "rule_type",
            ImmutableMap.of(
                "wrapper1", Value.wrapper(new Wrapper("type2", Value.stringValue("string")))));

    ParsedRuleKeyFile originalFile = createParsedFile(Paths.get("file1"), originalRuleKey);
    ParsedRuleKeyFile newFile = createParsedFile(Paths.get("file2"), newRuleKey);

    try (TargetScope ts =
        expectedPrinter.addTarget("//:target1", originalRuleKey.key, newRuleKey.key)) {
      try (PropertyScope rootPropertyScope = ts.addProperty("")) {
        try (PropertyScope ps = rootPropertyScope.addNestedProperty("wrapper1")) {
          ps.changed(
              originalFile,
              originalRuleKey.values.get("wrapper1"),
              newFile,
              newRuleKey.values.get("wrapper1"));
        }
      }
    }

    List<String> expectedLines = Arrays.asList(expectedStream.getOutputLines());

    differ.printDiff(originalFile, newFile);
    List<String> lines = Arrays.asList(stream.getOutputLines());

    Assert.assertEquals(expectedLines, lines);
  }
}
