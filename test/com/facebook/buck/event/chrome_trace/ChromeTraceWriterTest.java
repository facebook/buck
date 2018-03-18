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

package com.facebook.buck.event.chrome_trace;

import com.facebook.buck.event.chrome_trace.ChromeTraceEvent.Phase;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import org.junit.Assert;
import org.junit.Test;

public class ChromeTraceWriterTest {

  @Test
  public void simple() throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    ChromeTraceWriter writer = new ChromeTraceWriter(byteArrayOutputStream);
    writer.writeStart();
    writer.writeEvent(
        new ChromeTraceEvent("aa", "bb", Phase.BEGIN, 11, 12, 13, 14, ImmutableMap.of()));
    writer.writeEvent(
        new ChromeTraceEvent("cc", "dd", Phase.END, 11, 12, 13, 14, ImmutableMap.of()));
    writer.writeEnd();
    writer.close();

    JsonParser parser = ObjectMappers.createParser(byteArrayOutputStream.toByteArray());
    TreeNode node = parser.readValueAsTree();
    Assert.assertNotNull(node);
    Assert.assertTrue(node.getClass().getName(), node instanceof ArrayNode);
  }
}
