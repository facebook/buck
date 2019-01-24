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
import com.facebook.buck.log.thrift.rulekeys.NullValue;
import com.facebook.buck.log.thrift.rulekeys.Pattern;
import com.facebook.buck.log.thrift.rulekeys.RuleKeyHash;
import com.facebook.buck.log.thrift.rulekeys.Sha1;
import com.facebook.buck.log.thrift.rulekeys.TargetPath;
import com.facebook.buck.log.thrift.rulekeys.Value;
import com.facebook.buck.log.thrift.rulekeys.Wrapper;
import com.facebook.buck.tools.consistency.DifferState.MaxDifferencesException;
import com.facebook.buck.tools.consistency.RuleKeyDiffPrinter.TargetScope;
import com.facebook.buck.tools.consistency.RuleKeyDiffPrinter.TargetScope.PropertyScope;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.ParsedRuleKeyFile;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.RuleKeyNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.function.Function;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleKeyDiffPrinterTest {

  @Rule public ExpectedException expectedThrownException = ExpectedException.none();

  private ParsedRuleKeyFile sampleParsedFile;
  private TestPrintStream stream = TestPrintStream.create();
  private DiffPrinter diffPrinter;
  private RuleKeyDiffPrinter printer;

  @Before
  public void setUp() {
    this.diffPrinter = new DiffPrinter(stream, false);
    this.printer =
        new RuleKeyDiffPrinter(diffPrinter, new DifferState(DifferState.INFINITE_DIFFERENCES));

    RuleKeyNode ruleKey1 =
        new RuleKeyNode(
            new FullRuleKey(
                "fc3b3de4500fa30fd95007ff4bedcb9b4f92fecb",
                "//ruleKey1",
                "rule_type",
                ImmutableMap.of(
                    "list",
                    Value.containerList(
                        ImmutableList.of(
                            Value.ruleKeyHash(
                                new RuleKeyHash("08b7aee0eb8b1fc333516b66194f512a146c3ccb")))))));

    RuleKeyNode ruleKey2 =
        new RuleKeyNode(
            new FullRuleKey(
                "08b7aee0eb8b1fc333516b66194f512a146c3ccb",
                "//ruleKey2",
                "rule_type",
                ImmutableMap.of("value1", Value.numberValue(1))));

    this.sampleParsedFile =
        new ParsedRuleKeyFile(
            Paths.get("test.bin.out"),
            ImmutableMap.of(ruleKey1.ruleKey.name, ruleKey1),
            ImmutableMap.of(
                "fc3b3de4500fa30fd95007ff4bedcb9b4f92fecb", ruleKey1,
                "08b7aee0eb8b1fc333516b66194f512a146c3ccb", ruleKey2),
            Duration.ofNanos(100));
  }

  @Test
  public void doesNotPrintHeaderIfNoChangesToTarget() throws Exception {
    try (TargetScope ts = printer.addTarget("//:target1", "old hash", "new hash")) {}
    try (TargetScope ts = printer.addTarget("//:target2", "old hash", "new hash")) {
      try (PropertyScope ps = ts.addProperty("p1")) {
        ps.added(sampleParsedFile, Value.stringValue("s1"));
      }
    }

    // //:target1 shouldn't be printed since it has no changes
    String[] lines = stream.getOutputLines();
    Assert.assertEquals(2, lines.length);
    Assert.assertEquals("//:target2 (old hash vs new hash)", lines[0]);
    Assert.assertEquals("+ p1: s1", lines[1]);
  }

  @Test
  public void printsHeaderIfAnyChangesAdded() throws Exception {
    try (TargetScope ts = printer.addTarget("//:target1", "old hash", "new hash")) {
      try (PropertyScope ps = ts.addProperty("p1")) {
        ps.added(sampleParsedFile, Value.stringValue("s1"));
      }
    }

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(2, lines.length);
    Assert.assertEquals("//:target1 (old hash vs new hash)", lines[0]);
    Assert.assertEquals("+ p1: s1", lines[1]);
  }

  @Test
  public void printsMultipleDifferencesInCorrectOrder() throws Exception {
    try (TargetScope ts = printer.addTarget("//:target1", "old hash", "new hash")) {
      try (PropertyScope ps = ts.addProperty("proot")) {
        try (PropertyScope ps2 = ps.addNestedProperty("p1")) {
          ps2.added(sampleParsedFile, Value.stringValue("s1"));
        }
        try (PropertyScope ps2 = ps.addNestedProperty("p2")) {
          ps2.changed(
              sampleParsedFile, Value.stringValue("s2"), sampleParsedFile, Value.stringValue("s3"));
        }
        try (PropertyScope ps2 = ps.addNestedProperty("p3")) {
          ps2.removed(sampleParsedFile, Value.stringValue("s4"));
        }
      }
    }

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(5, lines.length);
    Assert.assertEquals("//:target1 (old hash vs new hash)", lines[0]);
    Assert.assertEquals("+ proot/p1: s1", lines[1]);
    Assert.assertEquals("- proot/p2: s2", lines[2]);
    Assert.assertEquals("+ proot/p2: s3", lines[3]);
    Assert.assertEquals("- proot/p3: s4", lines[4]);
  }

  @Test
  public void doesNotThrowExceptionWhenAddingAfterHittingExactlyMaxDiffs() throws Exception {
    printer = new RuleKeyDiffPrinter(diffPrinter, new DifferState(2));

    try (TargetScope ts = printer.addTarget("//:target1", "old hash", "new hash")) {
      try (PropertyScope ps2 = ts.addProperty("p1")) {
        ps2.added(sampleParsedFile, Value.stringValue("s1"));
      }
      try (PropertyScope ps2 = ts.addProperty("p2")) {
        ps2.added(sampleParsedFile, Value.stringValue("s2"));
      }
    }

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(3, lines.length);
    Assert.assertEquals("//:target1 (old hash vs new hash)", lines[0]);
    Assert.assertEquals("+ p1: s1", lines[1]);
    Assert.assertEquals("+ p2: s2", lines[2]);
  }

  @Test
  public void doesNotThrowExceptionWhenRemovingAfterHittingExactlyMaxDiffs() throws Exception {
    printer = new RuleKeyDiffPrinter(diffPrinter, new DifferState(2));

    try (TargetScope ts = printer.addTarget("//:target1", "old hash", "new hash")) {
      try (PropertyScope ps2 = ts.addProperty("p1")) {
        ps2.removed(sampleParsedFile, Value.stringValue("s1"));
      }
      try (PropertyScope ps2 = ts.addProperty("p2")) {
        ps2.removed(sampleParsedFile, Value.stringValue("s2"));
      }
    }

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(3, lines.length);
    Assert.assertEquals("//:target1 (old hash vs new hash)", lines[0]);
    Assert.assertEquals("- p1: s1", lines[1]);
    Assert.assertEquals("- p2: s2", lines[2]);
  }

  @Test
  public void doesNotThrowExceptionWhenChangingAfterHittingExactlyMaxDiffs() throws Exception {
    printer = new RuleKeyDiffPrinter(diffPrinter, new DifferState(2));

    try (TargetScope ts = printer.addTarget("//:target1", "old hash", "new hash")) {
      try (PropertyScope ps2 = ts.addProperty("p1")) {
        ps2.changed(
            sampleParsedFile, Value.stringValue("s1"), sampleParsedFile, Value.stringValue("s2"));
      }
      try (PropertyScope ps2 = ts.addProperty("p2")) {
        ps2.changed(
            sampleParsedFile, Value.stringValue("s3"), sampleParsedFile, Value.stringValue("s4"));
      }
    }
    String[] lines = stream.getOutputLines();
    Assert.assertEquals(5, lines.length);
    Assert.assertEquals("//:target1 (old hash vs new hash)", lines[0]);
    Assert.assertEquals("- p1: s1", lines[1]);
    Assert.assertEquals("+ p1: s2", lines[2]);
    Assert.assertEquals("- p2: s3", lines[3]);
    Assert.assertEquals("+ p2: s4", lines[4]);
  }

  @Test
  public void throwsExceptionWhenAddingAfterHittingMaxDiffs() throws Exception {
    expectedThrownException.expect(MaxDifferencesException.class);
    expectedThrownException.expectMessage("Stopping after finding 2 differences");
    printer = new RuleKeyDiffPrinter(diffPrinter, new DifferState(2));

    try (TargetScope ts = printer.addTarget("//:target1", "old hash", "new hash")) {
      try (PropertyScope ps2 = ts.addProperty("p1")) {
        ps2.added(sampleParsedFile, Value.stringValue("s1"));
      }
      try (PropertyScope ps2 = ts.addProperty("p2")) {
        ps2.added(sampleParsedFile, Value.stringValue("s2"));
      }
      try (PropertyScope ps2 = ts.addProperty("p3")) {
        ps2.added(sampleParsedFile, Value.stringValue("s3"));
      }
    }
  }

  @Test
  public void throwsExceptionWhenRemovingAfterHittingMaxDiffs() throws Exception {
    expectedThrownException.expect(MaxDifferencesException.class);
    expectedThrownException.expectMessage("Stopping after finding 2 differences");
    printer = new RuleKeyDiffPrinter(diffPrinter, new DifferState(2));

    try (TargetScope ts = printer.addTarget("//:target1", "old hash", "new hash")) {
      try (PropertyScope ps2 = ts.addProperty("p1")) {
        ps2.removed(sampleParsedFile, Value.stringValue("s1"));
      }
      try (PropertyScope ps2 = ts.addProperty("p2")) {
        ps2.removed(sampleParsedFile, Value.stringValue("s2"));
      }
      try (PropertyScope ps2 = ts.addProperty("p3")) {
        ps2.removed(sampleParsedFile, Value.stringValue("s3"));
      }
    }
  }

  @Test
  public void throwsExceptionWhenChangingAfterHittingMaxDiffs() throws Exception {

    expectedThrownException.expect(MaxDifferencesException.class);
    expectedThrownException.expectMessage("Stopping after finding 2 differences");
    printer = new RuleKeyDiffPrinter(diffPrinter, new DifferState(2));

    try (TargetScope ts = printer.addTarget("//:target1", "old hash", "new hash")) {
      try (PropertyScope ps2 = ts.addProperty("p1")) {
        ps2.changed(
            sampleParsedFile, Value.stringValue("s1"), sampleParsedFile, Value.stringValue("s2"));
      }
      try (PropertyScope ps2 = ts.addProperty("p2")) {
        ps2.changed(
            sampleParsedFile, Value.stringValue("s3"), sampleParsedFile, Value.stringValue("s4"));
      }
      try (PropertyScope ps2 = ts.addProperty("p3")) {
        ps2.changed(
            sampleParsedFile, Value.stringValue("s5"), sampleParsedFile, Value.stringValue("s6"));
      }
    }
  }

  @Test
  public void convertsToReadableStringProperly() {
    Assert.assertEquals(
        "string value",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.stringValue("string value")));

    Assert.assertEquals(
        "1.5", RuleKeyDiffPrinter.valueAsReadableString(sampleParsedFile, Value.numberValue(1.5)));

    Assert.assertEquals(
        "true", RuleKeyDiffPrinter.valueAsReadableString(sampleParsedFile, Value.boolValue(true)));

    Assert.assertEquals(
        "null",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.nullValue(new NullValue())));

    Assert.assertEquals(
        "Path: some path, hash: some hash",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.hashedPath(new HashedPath("some path", "some hash"))));

    Assert.assertEquals(
        "Path: some path",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.path(new NonHashedPath("some path"))));

    Assert.assertEquals(
        "Regex Pattern: \\w+",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.pattern(new Pattern("\\w+"))));

    Assert.assertEquals(
        "Sha1: some sha",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.sha1Hash(new Sha1("some sha"))));

    Assert.assertEquals(
        "Byte array length: 1234",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.byteArray(new ByteArray(1234))));

    Assert.assertEquals(
        "Map: Length: 2",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile,
            Value.containerMap(
                ImmutableMap.of(
                    "key1", Value.stringValue("value1"), "key2", Value.stringValue("value2")))));

    Assert.assertEquals(
        "List: Length: 2",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile,
            Value.containerList(
                ImmutableList.of(Value.stringValue("value1"), Value.stringValue("value2")))));

    Assert.assertEquals(
        "RuleKey: unknown hash",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.ruleKeyHash(new RuleKeyHash("unknown hash"))));

    Assert.assertEquals(
        "RuleKey(08b7aee0eb8b1fc333516b66194f512a146c3ccb) //ruleKey2",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile,
            Value.ruleKeyHash(new RuleKeyHash("08b7aee0eb8b1fc333516b66194f512a146c3ccb"))));

    Assert.assertEquals(
        "ArchiveMemberPath: archive path!member path, hash: some hash",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile,
            Value.archiveMemberPath(
                new ArchiveMemberPath("archive path", "member path", "some hash"))));

    Assert.assertEquals(
        "BuildRuleType: rule type",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.buildRuleType(new BuildRuleType("rule type"))));

    Assert.assertEquals(
        "Wrapper: OPTIONAL/test string",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile,
            Value.wrapper(new Wrapper("OPTIONAL", Value.stringValue("test string")))));

    Assert.assertEquals(
        "BuildTarget: //:test",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.buildTarget(new BuildTarget("//:test"))));

    Assert.assertEquals(
        "TargetPath: target path",
        RuleKeyDiffPrinter.valueAsReadableString(
            sampleParsedFile, Value.targetPath(new TargetPath("target path"))));
  }

  @Test
  public void getsNameForRuleKeyWithArgProperty() {
    FullRuleKey ruleKey =
        new FullRuleKey(
            "0e869439e0ebd4ee13bc802ec2eae81a06d8d66a",
            "",
            "rule_type",
            ImmutableMap.of("arg", Value.stringValue("sample name")));
    String name = RuleKeyDiffPrinter.getRuleKeyName(sampleParsedFile, ruleKey);
    Assert.assertEquals("argument: sample name", name);
  }

  @Test
  public void getsNameForRuleKeyWithNameSetProperly() {
    FullRuleKey ruleKey =
        new FullRuleKey(
            "0e869439e0ebd4ee13bc802ec2eae81a06d8d66a",
            "//:rule_name",
            "rule_type",
            ImmutableMap.of());
    String name = RuleKeyDiffPrinter.getRuleKeyName(sampleParsedFile, ruleKey);
    Assert.assertEquals("//:rule_name", name);
  }

  @Test
  public void returnsUnknownNameIfNameAndArgPropertyAreMissing() {
    FullRuleKey ruleKey =
        new FullRuleKey(
            "0e869439e0ebd4ee13bc802ec2eae81a06d8d66a", "", "rule_type", ImmutableMap.of());
    String name = RuleKeyDiffPrinter.getRuleKeyName(sampleParsedFile, ruleKey);
    Assert.assertEquals("UNKNOWN NAME", name);
  }

  @Test
  public void returnsNameForVariousArgPropertyTypesWithMissingName() {
    Function<Value, FullRuleKey> f =
        v ->
            new FullRuleKey(
                "0e869439e0ebd4ee13bc802ec2eae81a06d8d66a",
                "",
                "rule_type",
                ImmutableMap.of("arg", v));

    Assert.assertEquals(
        "argument: s1",
        RuleKeyDiffPrinter.getRuleKeyName(sampleParsedFile, f.apply(Value.stringValue("s1"))));

    Assert.assertEquals(
        "argument: 1.5",
        RuleKeyDiffPrinter.getRuleKeyName(sampleParsedFile, f.apply(Value.numberValue(1.5))));

    Assert.assertEquals(
        "argument: true",
        RuleKeyDiffPrinter.getRuleKeyName(sampleParsedFile, f.apply(Value.boolValue(true))));

    Assert.assertEquals(
        "argument: null",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile, f.apply(Value.nullValue(new NullValue()))));

    Assert.assertEquals(
        "argument: some path",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile, f.apply(Value.hashedPath(new HashedPath("some path", "some hash")))));

    Assert.assertEquals(
        "argument: some path",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile, f.apply(Value.path(new NonHashedPath("some path")))));

    Assert.assertEquals(
        "argument: \\w+",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile, f.apply(Value.pattern(new Pattern("\\w+")))));

    Assert.assertEquals(
        "argument: some sha",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile, f.apply(Value.sha1Hash(new Sha1("some sha")))));

    Assert.assertEquals(
        "argument: ByteArray, length 1234",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile, f.apply(Value.byteArray(new ByteArray(1234)))));

    Assert.assertEquals(
        "argument: key1: value1, key2: value2",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile,
            f.apply(
                Value.containerMap(
                    ImmutableMap.of(
                        "key1",
                        Value.stringValue("value1"),
                        "key2",
                        Value.stringValue("value2"))))));

    Assert.assertEquals(
        "argument: value1, value2",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile,
            f.apply(
                Value.containerList(
                    ImmutableList.of(Value.stringValue("value1"), Value.stringValue("value2"))))));

    Assert.assertEquals(
        "argument: UNKNOWN RULE KEY",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile, f.apply(Value.ruleKeyHash(new RuleKeyHash("unknown hash")))));

    Assert.assertEquals(
        "argument: //ruleKey2",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile,
            f.apply(
                Value.ruleKeyHash(new RuleKeyHash("08b7aee0eb8b1fc333516b66194f512a146c3ccb")))));

    Assert.assertEquals(
        "argument: archive path!member path",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile,
            f.apply(
                Value.archiveMemberPath(
                    new ArchiveMemberPath("archive path", "member path", "some hash")))));

    Assert.assertEquals(
        "argument: rule type",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile, f.apply(Value.buildRuleType(new BuildRuleType("rule type")))));

    Assert.assertEquals(
        "argument: test string",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile,
            f.apply(Value.wrapper(new Wrapper("OPTIONAL", Value.stringValue("test string"))))));

    Assert.assertEquals(
        "argument: //:test",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile, f.apply(Value.buildTarget(new BuildTarget("//:test")))));

    Assert.assertEquals(
        "argument: target path",
        RuleKeyDiffPrinter.getRuleKeyName(
            sampleParsedFile, f.apply(Value.targetPath(new TargetPath("target path")))));
  }
}
