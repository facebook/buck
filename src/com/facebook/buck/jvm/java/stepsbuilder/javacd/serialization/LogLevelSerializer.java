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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization;

import static com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.SerializationUtil.createNotSupportedException;

import com.facebook.buck.javacd.model.JarParameters;
import java.util.logging.Level;

/** {@link Level} to protobuf serializer */
class LogLevelSerializer {

  private LogLevelSerializer() {}

  /** Serializes {@link Level} into javacd model's {@link JarParameters.LogLevel}. */
  public static JarParameters.LogLevel serialize(Level level) {
    return com.facebook.buck.javacd.model.JarParameters.LogLevel.valueOf(level.getName());
  }

  /** Deserializes javacd model's {@link JarParameters.LogLevel} into {@link Level}. */
  public static Level deserialize(JarParameters.LogLevel level) {
    switch (level) {
      case ALL:
        return Level.ALL;
      case OFF:
        return Level.OFF;
      case CONFIG:
        return Level.CONFIG;

      case SEVERE:
        return Level.SEVERE;
      case WARNING:
        return Level.WARNING;
      case INFO:
        return Level.INFO;

      case FINE:
        return Level.FINE;
      case FINER:
        return Level.FINER;
      case FINEST:
        return Level.FINEST;

      case UNRECOGNIZED:
      case UNKNOWN:
      default:
        throw createNotSupportedException(level);
    }
  }
}
