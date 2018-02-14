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

package com.facebook.buck.log;

import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.log.thrift.rulekeys.Value;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.protocol.TCompactProtocol;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ThriftRuleKeyLoggerTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private Path logDir;

  @Before
  public void setUp() throws InterruptedException, IOException {
    logDir = temporaryFolder.newFolder("root");
  }

  @Test
  public void testWritesToGivenFileAndCreatesDirectories() throws Exception {
    Path logPath = logDir.resolve("logs").resolve("out.log").toAbsolutePath();
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      Assert.assertNotNull(logger);
      logger.write(
          new FullRuleKey(
              "rule_key", "name", "rule_type", ImmutableMap.of("key", Value.stringValue("value"))));
    }
    Assert.assertTrue(logPath.toFile().exists());

    ByteBuffer lengthBuf = ByteBuffer.allocate(4);
    try (FileInputStream logFileStream = new FileInputStream(logPath.toFile())) {
      Assert.assertTrue(logFileStream.available() > 4);

      logFileStream.read(lengthBuf.array());
      int length = lengthBuf.getInt();

      Assert.assertEquals(length, logFileStream.available()); // Only should have one object

      byte[] serialized = new byte[length];
      logFileStream.read(serialized);
      TDeserializer serializer = new TDeserializer(new TCompactProtocol.Factory());
      FullRuleKey ruleKey = new FullRuleKey();
      serializer.deserialize(ruleKey, serialized);

      Assert.assertEquals("rule_key", ruleKey.key);
      Assert.assertEquals("name", ruleKey.name);
      Assert.assertEquals("rule_type", ruleKey.type);
      Assert.assertEquals(1, ruleKey.values.size());
      Assert.assertEquals("value", ruleKey.values.get("key").getStringValue());
    }
  }

  private class ErrorWriter extends OutputStream {
    public int writeCalls = 0;

    @Override
    public void write(int b) throws IOException {
      writeCalls++;
      throw new IOException("Oh, no! An error!");
    }
  }

  @Test
  public void testSwallowsWriteExceptions() {
    ErrorWriter writer = new ErrorWriter();
    try (ThriftRuleKeyLogger logger = new ThriftRuleKeyLogger(writer)) {
      logger.write(
          new FullRuleKey(
              "rule_key", "name", "rule_type", ImmutableMap.of("key", Value.stringValue("value"))));
    }
    Assert.assertEquals(1, writer.writeCalls);
  }
}
