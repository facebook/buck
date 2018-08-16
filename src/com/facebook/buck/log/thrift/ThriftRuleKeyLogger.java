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

package com.facebook.buck.log.thrift;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TCompactProtocol;

/**
 * Writes out length prefixed ThriftCompact serialized representations of Rule Keys to an output
 * stream.
 */
public class ThriftRuleKeyLogger implements AutoCloseable {
  private final OutputStream writer;

  private static Logger logger = Logger.get(ThriftRuleKeyLogger.class);

  /**
   * Creates a ThriftRuleKeyLogger
   *
   * @param writer The stream to write serialized data to
   */
  public ThriftRuleKeyLogger(OutputStream writer) {
    this.writer = writer;
  }

  /** Close the output stream */
  @Override
  public void close() {
    try {
      this.writer.close();
    } catch (IOException e) {
      logger.warn(e, "Could not close ruleKeyLogger");
    }
  }

  /**
   * Writes a serialized form of the rule key to the output stream. Serialization is Thrift Compact,
   * with a 4 byte length prefix. Serialization or write errors are swallowed, and logged so that
   * failures to write do not terminate buck
   *
   * @param ruleKey The non-null ruleKey to write out to the ifle
   */
  public void write(FullRuleKey ruleKey) {
    TSerializer serializer = new TSerializer(new TCompactProtocol.Factory());
    byte[] out;
    try {
      out = serializer.serialize(ruleKey);
    } catch (TException e) {
      logger.warn(
          e, "Could not serialize key for target %s with hash %s", ruleKey.name, ruleKey.key);
      return;
    }
    synchronized (this) {
      try {
        writer.write(ByteBuffer.allocate(4).putInt(out.length).array());
        writer.write(out);
      } catch (IOException e) {
        logger.warn(e, "Could not write key for target %s with hash %s", ruleKey.name, ruleKey.key);
      }
    }
  }

  /**
   * Create an instance of a logger that writes to the given file name
   *
   * <p>An attempt is made to create all subdirectories before opening the file
   *
   * @param filename The file to write thrift data to
   * @return A binary logger
   * @throws FileNotFoundException Thrown if the subdirectory containing the filename could not be
   *     created
   */
  public static ThriftRuleKeyLogger create(Path filename) throws IOException {
    Files.createDirectories(filename.getParent());
    OutputStream writer = new BufferedOutputStream(Files.newOutputStream(filename));
    return new ThriftRuleKeyLogger(writer);
  }
}
