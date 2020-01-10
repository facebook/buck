/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util;

import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;

public class ThriftRuleKeyDeserializer {
  /**
   * Reads in a list of rule keys from a file
   *
   * @param logPath The path to the file
   * @return A list of FullRuleKey objects from the file
   * @throws IOException Could not read the file
   * @throws TException Could not deserialize an entry from the file
   */
  public static List<FullRuleKey> readRuleKeys(Path logPath) throws IOException, TException {
    ByteBuffer lengthBuf = ByteBuffer.allocate(4);
    List<FullRuleKey> ret = new ArrayList<>();
    try (FileInputStream logFileStream = new FileInputStream(logPath.toAbsolutePath().toString())) {
      while (logFileStream.available() > 0) {
        logFileStream.read(lengthBuf.array());
        int length = lengthBuf.getInt();
        lengthBuf.rewind();

        byte[] serialized = new byte[length];
        logFileStream.read(serialized);
        TDeserializer serializer = new TDeserializer(new TCompactProtocol.Factory());
        FullRuleKey ruleKey = new FullRuleKey();
        serializer.deserialize(ruleKey, serialized);
        ret.add(ruleKey);
      }
      return ret;
    }
  }
}
