/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.google.common.hash.HashCode;

import java.nio.file.Path;

/**
 * Interface used to implement a logging for the RuleKey structure.
 */
public interface RuleKeyLogger {

  void registerRuleKey(RuleKey ruleKey);

  Scope pushKey(String key);
  Scope pushMap();
  Scope pushMapKey();
  Scope pushMapValue();
  Scope pushSourceWithFlags();

  void addPath(Path path, HashCode hashCode);
  void addNonHashingPath(String path);

  void addNullValue();
  void addValue(String value);
  void addValue(boolean value);
  void addValue(Enum<?> value);
  void addValue(double value);
  void addValue(float value);
  void addValue(int value);
  void addValue(long value);
  void addValue(short value);
  void addValue(BuildRuleType value);
  void addValue(RuleKey value);
  void addValue(BuildTarget value);
  void addValue(SourceRoot value);
  void addValue(byte[] value);

  interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
