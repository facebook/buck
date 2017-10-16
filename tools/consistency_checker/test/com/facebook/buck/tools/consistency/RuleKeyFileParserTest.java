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
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.ParseException;
import com.facebook.buck.tools.consistency.RuleKeyFileParser.ParsedFile;
import com.google.common.collect.ImmutableMap;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleKeyFileParserTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private String logPath;

  @Before
  public void setUp() throws InterruptedException, IOException {
    logPath = temporaryFolder.newFile("out.bin.log").toAbsolutePath().toString();
  }

  @Test
  public void parsesFileProperly() throws FileNotFoundException, ParseException {
    FullRuleKey ruleKey1 = new FullRuleKey("key1", "//:name1", "DEFAULT", ImmutableMap.of());
    FullRuleKey ruleKey2 = new FullRuleKey("key2", "//:name1", "other_type", ImmutableMap.of());
    FullRuleKey ruleKey3 = new FullRuleKey("key3", "//:name2", "OTHER", ImmutableMap.of());
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      logger.write(ruleKey1);
      logger.write(ruleKey2);
      logger.write(ruleKey3);
    }

    RuleKeyFileParser parser = new RuleKeyFileParser();
    ParsedFile parsedFile = parser.parseFile(logPath, "//:name1");

    Assert.assertEquals("key1", parsedFile.rootHash);
    Assert.assertEquals(logPath, parsedFile.filename);
    Assert.assertTrue(parsedFile.parseTime.toNanos() > 0);
    Assert.assertEquals(3, parsedFile.rules.size());
    Assert.assertEquals(ruleKey1, parsedFile.rules.get("key1").ruleKey);
    Assert.assertEquals(ruleKey2, parsedFile.rules.get("key2").ruleKey);
    Assert.assertEquals(ruleKey3, parsedFile.rules.get("key3").ruleKey);
  }

  @Test
  public void throwsIfTargetNotFound() throws FileNotFoundException, ParseException {
    expectedException.expectMessage("Could not find //:invalid_name in ");
    expectedException.expect(ParseException.class);

    FullRuleKey ruleKey1 = new FullRuleKey("key1", "//:name1", "DEFAULT", ImmutableMap.of());
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      logger.write(ruleKey1);
    }

    RuleKeyFileParser parser = new RuleKeyFileParser();
    parser.parseFile(logPath, "//:invalid_name");
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
        new DataOutputStream(new FileOutputStream(logPath, true))) {
      outputStream.writeInt(1234);
      outputStream.writeInt(0); // Add an extra 4 bytes
      outputStream.flush();
    }

    RuleKeyFileParser parser = new RuleKeyFileParser();
    parser.parseFile(logPath, "//:name1");
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
        new DataOutputStream(new FileOutputStream(logPath, true))) {
      outputStream.writeInt(8);
      outputStream.writeInt(1234);
      outputStream.writeInt(2345);
      outputStream.flush();
    }

    RuleKeyFileParser parser = new RuleKeyFileParser();
    parser.parseFile(logPath, "//:name1");
  }
}
