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
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.protocol.TCompactProtocol;

/** A class that can parse out length prefixed, thrift compact encoded rule keys. */
public class RuleKeyFileParser {

  /**
   * A node in the set of all rule keys. When parsing, it tracks whether the node has been visited
   * yet or not.
   */
  static class RuleKeyNode {

    public final FullRuleKey ruleKey;
    public boolean visited = false;

    public RuleKeyNode(FullRuleKey ruleKey) {
      this.ruleKey = ruleKey;
    }
  }

  /** A parsed rule key file with details of the parse, and all rules contained in the file. */
  static class ParsedFile {

    public final String filename;
    public final RuleKeyNode rootNode;
    public final Map<String, RuleKeyNode> rules;
    public final Duration parseTime;

    public ParsedFile(
        String filename, RuleKeyNode rootNode, Map<String, RuleKeyNode> rules, Duration parseTime) {
      this.filename = filename;
      this.rootNode = rootNode;
      this.rules = rules;
      this.parseTime = parseTime;
    }
  }

  /**
   * Represents an error that occurred when parsing the log file. Wraps any internal exceptions such
   * as IO exceptions and thrift errors.
   */
  public class ParseException extends Exception {

    public ParseException(String formatString, Object... formatArgs) {
      super(String.format(formatString, formatArgs));
    }

    public ParseException(Throwable originalException, String formatString, Object... formatArgs) {
      super(String.format(formatString, formatArgs), originalException);
    }
  }

  /** Arbitrary estimate of an average rule key size when serialized */
  public final int THRIFT_STRUCT_SIZE = 300;

  /**
   * Parse a thrift compact serialized file
   *
   * @param filename The name of the file
   * @param targetName The name of the target that should be found
   * @return A {@link ParsedFile} object that all deserialized rules, and the rule key hash of the
   *     specified target
   * @throws ParseException If an IO or serialization error occurs, or if the target could not be
   *     found in the file
   */
  public ParsedFile parseFile(String filename, String targetName) throws ParseException {
    long startNanos = System.nanoTime();

    HashMap<String, RuleKeyNode> ret = null;
    RuleKeyNode rootNode = null;

    ByteBuffer lengthBuf = ByteBuffer.allocate(4);
    try (FileInputStream fileInputStream = new FileInputStream(filename)) {
      ret = new HashMap<>(fileInputStream.available() / THRIFT_STRUCT_SIZE);

      while (fileInputStream.available() >= 4) {
        fileInputStream.read(lengthBuf.array());
        int length = lengthBuf.getInt();
        lengthBuf.rewind();

        byte[] serialized = new byte[length];
        int bytesRead = fileInputStream.read(serialized);
        if (bytesRead != length) {
          throw new ParseException(
              "Invalid length specified. Expected %s bytes, only got %s", length, bytesRead);
        }
        TDeserializer deserializer = new TDeserializer(new TCompactProtocol.Factory());
        FullRuleKey ruleKey = new FullRuleKey();
        deserializer.deserialize(ruleKey, serialized);
        // The thrift deserializer doesn't blow up on invalid data, it just sets all fields to
        // null. 'key' is required, so if it's null, we failed to deserialize. Yes, deserialize()
        // /should/ throw a TException, but it doesn't.
        if (ruleKey.key == null) {
          throw new ParseException("Could not deserialize array of size %s", serialized.length);
        }
        RuleKeyNode newNode = new RuleKeyNode(ruleKey);
        if ("DEFAULT".equals(ruleKey.type) && targetName.equals(ruleKey.name)) {
          rootNode = newNode;
        }
        ret.put(ruleKey.key, newNode);
      }
    } catch (Exception e) {
      throw new ParseException(e, "Error reading %s: %s", filename, e.getMessage());
    }
    if (rootNode == null) {
      throw new ParseException("Could not find %s in %s", targetName, filename);
    }
    Duration runtime = Duration.ofNanos(System.nanoTime() - startNanos);

    return new ParsedFile(filename, rootNode, ret, runtime);
  }
}
