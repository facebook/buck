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

import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.log.thrift.rulekeys.Value;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.ParsedRuleKeyFile;
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleKeyFileParserTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private Path logPath;
  private RuleKeyLogFileReader reader = new RuleKeyLogFileReader();

  @Before
  public void setUp() throws IOException {
    logPath = temporaryFolder.newFile("out.bin.log").toAbsolutePath();
  }

  @Test
  public void parsesFileProperly() throws IOException, ParseException {
    FullRuleKey ruleKey1 = new FullRuleKey("key1", "//:name1", "DEFAULT", ImmutableMap.of());
    FullRuleKey ruleKey2 = new FullRuleKey("key2", "//:name1", "other_type", ImmutableMap.of());
    FullRuleKey ruleKey3 = new FullRuleKey("key3", "//:name2", "OTHER", ImmutableMap.of());
    FullRuleKey ruleKey4 = new FullRuleKey("key4", "//:name4", "DEFAULT", ImmutableMap.of());
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      logger.write(ruleKey1);
      logger.write(ruleKey2);
      logger.write(ruleKey3);
      logger.write(ruleKey4);
    }

    RuleKeyFileParser parser = new RuleKeyFileParser(reader);
    ParsedRuleKeyFile parsedFile =
        parser.parseFile(logPath, ImmutableSet.of("//:name1", "//:name4"));

    Assert.assertEquals("key1", parsedFile.rootNodes.get("//:name1").ruleKey.key);
    Assert.assertEquals("key4", parsedFile.rootNodes.get("//:name4").ruleKey.key);
    Assert.assertEquals(logPath, parsedFile.filename);
    Assert.assertTrue(parsedFile.parseTime.toNanos() > 0);
    Assert.assertEquals(4, parsedFile.rules.size());
    Assert.assertEquals(ruleKey1, parsedFile.rules.get("key1").ruleKey);
    Assert.assertEquals(ruleKey2, parsedFile.rules.get("key2").ruleKey);
    Assert.assertEquals(ruleKey3, parsedFile.rules.get("key3").ruleKey);
    Assert.assertEquals(ruleKey4, parsedFile.rules.get("key4").ruleKey);
  }

  @Test
  public void throwsIfTargetNotFound() throws IOException, ParseException {
    expectedException.expectMessage("Could not find //:invalid_name in ");
    expectedException.expect(ParseException.class);

    FullRuleKey ruleKey1 = new FullRuleKey("key1", "//:name1", "DEFAULT", ImmutableMap.of());
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      logger.write(ruleKey1);
    }

    RuleKeyFileParser parser = new RuleKeyFileParser(reader);
    parser.parseFile(logPath, ImmutableSet.of("//:name1", "//:invalid_name"));
  }

  @Test
  public void throwsOnBadLength() throws ParseException, IOException {
    expectedException.expectMessage("Invalid length specified. Expected 1234 bytes, only got 4");
    expectedException.expect(ParseException.class);

    FullRuleKey ruleKey1 = new FullRuleKey("key1", "//:name1", "DEFAULT", ImmutableMap.of());
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      logger.write(ruleKey1);
    }
    try (DataOutputStream outputStream =
        new DataOutputStream(new FileOutputStream(logPath.toFile(), true))) {
      outputStream.writeInt(1234);
      outputStream.writeInt(0); // Add an extra 4 bytes
      outputStream.flush();
    }

    RuleKeyFileParser parser = new RuleKeyFileParser(reader);
    parser.parseFile(logPath, ImmutableSet.of("//:name1"));
  }

  @Test
  public void throwsOnDeserializationError() throws ParseException, IOException {
    expectedException.expectMessage("Could not deserialize array of size 8");
    expectedException.expect(ParseException.class);

    FullRuleKey ruleKey1 = new FullRuleKey("key1", "//:name1", "DEFAULT", ImmutableMap.of());
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      logger.write(ruleKey1);
    }
    try (DataOutputStream outputStream =
        new DataOutputStream(new FileOutputStream(logPath.toFile(), true))) {
      outputStream.writeInt(8);
      outputStream.writeInt(1234);
      outputStream.writeInt(2345);
      outputStream.flush();
    }

    RuleKeyFileParser parser = new RuleKeyFileParser(reader);
    parser.parseFile(logPath, ImmutableSet.of("//:name1"));
  }

  @Test
  public void doesNotThrowOnDuplicateKeyOfSameValue() throws ParseException, IOException {
    FullRuleKey ruleKey1 = new FullRuleKey("key1", "//:name1", "DEFAULT", ImmutableMap.of());
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      logger.write(ruleKey1);
    }

    RuleKeyFileParser parser = new RuleKeyFileParser(reader);
    ParsedRuleKeyFile parsedFile = parser.parseFile(logPath, ImmutableSet.of("//:name1"));

    Assert.assertEquals("key1", parsedFile.rootNodes.get("//:name1").ruleKey.key);
    Assert.assertEquals(logPath, parsedFile.filename);
    Assert.assertTrue(parsedFile.parseTime.toNanos() > 0);
    Assert.assertEquals(1, parsedFile.rules.size());
    Assert.assertEquals(ruleKey1, parsedFile.rules.get("key1").ruleKey);
  }

  @Test
  public void throwsOnDuplicateKeyOfDifferentValue() throws ParseException, IOException {
    expectedException.expect(ParseException.class);
    expectedException.expectMessage("Found two rules with the same key, but different values");

    FullRuleKey ruleKey1 = new FullRuleKey("key1", "//:name1", "DEFAULT", ImmutableMap.of());
    FullRuleKey ruleKey2 =
        new FullRuleKey(
            "key1", "//:name1", "DEFAULT", ImmutableMap.of("value", Value.stringValue("string")));
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      logger.write(ruleKey1);
      logger.write(ruleKey2);
    }

    RuleKeyFileParser parser = new RuleKeyFileParser(reader);
    ParsedRuleKeyFile parsedFile = parser.parseFile(logPath, ImmutableSet.of("//:name1"));
  }

  @Test
  public void handlesRecursiveRules() throws ParseException, IOException {
    FullRuleKey ruleKey1 = new FullRuleKey("key1", "//src:name1", "DEFAULT", ImmutableMap.of());
    FullRuleKey ruleKey2 = new FullRuleKey("key2", "//test:name2", "DEFAULT", ImmutableMap.of());
    FullRuleKey ruleKey3 =
        new FullRuleKey("key3", "//test/foo:name3", "DEFAULT", ImmutableMap.of());
    FullRuleKey ruleKey4 = new FullRuleKey("key4", "//:name4", "DEFAULT", ImmutableMap.of());
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      logger.write(ruleKey1);
      logger.write(ruleKey2);
      logger.write(ruleKey3);
      logger.write(ruleKey4);
    }

    RuleKeyFileParser parser = new RuleKeyFileParser(reader);
    ParsedRuleKeyFile parsedFile =
        parser.parseFile(
            logPath,
            ImmutableSet.of("//src/...", "//test:", "//non_existent:", "//non_existent2/..."));

    Assert.assertEquals(2, parsedFile.rootNodes.size());
    Assert.assertEquals("key1", parsedFile.rootNodes.get("//src:name1").ruleKey.key);
    Assert.assertEquals("key2", parsedFile.rootNodes.get("//test:name2").ruleKey.key);
    Assert.assertEquals(logPath, parsedFile.filename);
    Assert.assertTrue(parsedFile.parseTime.toNanos() > 0);
    Assert.assertEquals(4, parsedFile.rules.size());
    Assert.assertEquals(ruleKey1, parsedFile.rules.get("key1").ruleKey);
    Assert.assertEquals(ruleKey2, parsedFile.rules.get("key2").ruleKey);
    Assert.assertEquals(ruleKey3, parsedFile.rules.get("key3").ruleKey);
    Assert.assertEquals(ruleKey4, parsedFile.rules.get("key4").ruleKey);
  }
}
