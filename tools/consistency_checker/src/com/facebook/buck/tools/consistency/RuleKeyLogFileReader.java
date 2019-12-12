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

package com.facebook.buck.tools.consistency;

import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;

/** A generic way to read length prefixed thrift compact serialized rule keys from a file */
public class RuleKeyLogFileReader {

  /**
   * Represents an error that occurred when parsing the log file. Wraps any internal exceptions such
   * as IO exceptions and thrift errors.
   */
  public static class ParseException extends Exception {

    public ParseException(Path filename, String formatString, Object... formatArgs) {
      super(String.format("%s: %s", filename, String.format(formatString, formatArgs)));
    }

    public ParseException(
        Throwable originalException, Path filename, String formatString, Object... formatArgs) {
      super(
          String.format("%s: %s", filename, String.format(formatString, formatArgs)),
          originalException);
    }
  }

  /**
   * Reads a file in and runs a predicate on each deserialized rule key
   *
   * @param filename The name of the file to read
   * @param visitor Called for each rule key that gets deserialized. Return {@code true} if
   *     deserialization should halt, or false if it should proceed
   * @throws ParseException There was an error reading data, or invalid data was found in the file
   */
  public void readFile(Path filename, Predicate<FullRuleKey> visitor) throws ParseException {
    ByteBuffer lengthBuf = ByteBuffer.allocate(4);
    try (FileInputStream fileInputStream = new FileInputStream(filename.toFile())) {
      while (fileInputStream.available() >= 4) {
        fileInputStream.read(lengthBuf.array());
        int length = lengthBuf.getInt();
        lengthBuf.rewind();

        byte[] serialized = new byte[length];
        int bytesRead = fileInputStream.read(serialized);
        if (bytesRead != length) {
          throw new ParseException(
              filename,
              "Invalid length specified. Expected %s bytes, only got %s",
              length,
              bytesRead);
        }
        TDeserializer deserializer = new TDeserializer(new TCompactProtocol.Factory());
        FullRuleKey ruleKey = new FullRuleKey();
        deserializer.deserialize(ruleKey, serialized);
        // The thrift deserializer doesn't blow up on invalid data, it just sets all fields to
        // null. 'key' is required, so if it's null, we failed to deserialize. Yes, deserialize()
        // /should/ throw a TException, but it doesn't.
        if (ruleKey.key == null) {
          throw new ParseException(
              filename, "Could not deserialize array of size %s", serialized.length);
        }
        if (visitor.test(ruleKey)) {
          return;
        }
      }
    } catch (TException | IOException e) {
      throw new ParseException(e, filename, "Error reading file: %s", e.getMessage());
    }
  }
}
