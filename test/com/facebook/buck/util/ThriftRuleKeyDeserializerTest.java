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

package com.facebook.buck.util;

import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.thrift.TException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ThriftRuleKeyDeserializerTest {
  @Rule public TemporaryPaths temp = new TemporaryPaths();

  @Test
  public void returnsEmptyListFromEmptyFile() throws IOException, TException {
    Path logPath = temp.newFile("log.bin");
    List<FullRuleKey> keys = ThriftRuleKeyDeserializer.readRuleKeys(logPath);
    Assert.assertEquals(0, keys.size());
  }

  @Test
  public void deserializesSerializedData() throws IOException, TException {
    Path logPath = temp.newFile("log.bin");

    FullRuleKey ruleKey1 = new FullRuleKey("key1", "name1", "type1", ImmutableMap.of());
    FullRuleKey ruleKey2 = new FullRuleKey("key2", "name2", "type2", ImmutableMap.of());
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath.toAbsolutePath())) {
      logger.write(ruleKey1);
      logger.write(ruleKey2);
    }

    List<FullRuleKey> keys = ThriftRuleKeyDeserializer.readRuleKeys(logPath);

    Assert.assertEquals(2, keys.size());
    Assert.assertEquals(ruleKey1, keys.get(0));
    Assert.assertEquals(ruleKey2, keys.get(1));
  }
}
