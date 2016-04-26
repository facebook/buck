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

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.model.BuildTarget;
import com.google.common.hash.HashCode;

import java.nio.file.Path;

/**
 * Null object pattern for RuleKeyLogger.
 */
public class NullRuleKeyLogger implements RuleKeyLogger {

  private static final Scope NO_OP_SCOPE = new Scope() {
    @Override
    public void close() {
      // Intentional no-op (duh!).
    }
  };

  @Override
  public void registerRuleKey(RuleKey ruleKey) {
  }

  @Override
  public Scope pushKey(String key) {
    return NO_OP_SCOPE;
  }

  @Override
  public Scope pushMap() {
    return NO_OP_SCOPE;
  }

  @Override
  public Scope pushMapKey() {
    return NO_OP_SCOPE;
  }

  @Override
  public Scope pushMapValue() {
    return NO_OP_SCOPE;
  }

  @Override
  public Scope pushSourceWithFlags() {
    return NO_OP_SCOPE;
  }

  @Override
  public void addArchiveMemberPath(
      ArchiveMemberPath archiveMemberPath, HashCode hashCode) {
  }

  @Override
  public void addPath(Path path, HashCode hashCode) {
  }

  @Override
  public void addNonHashingPath(String path) {
  }

  @Override
  public void addNullValue() {
  }

  @Override
  public void addValue(String value) {
  }

  @Override
  public void addValue(boolean value) {
  }

  @Override
  public void addValue(Enum<?> value) {
  }

  @Override
  public void addValue(double value) {
  }

  @Override
  public void addValue(float value) {
  }

  @Override
  public void addValue(int value) {
  }

  @Override
  public void addValue(long value) {
  }

  @Override
  public void addValue(short value) {
  }

  @Override
  public void addValue(BuildRuleType value) {
  }

  @Override
  public void addValue(RuleKey value) {
  }

  @Override
  public void addValue(BuildTarget value) {
  }

  @Override
  public void addValue(SourceRoot value) {
  }

  @Override
  public void addValue(byte[] value) {
  }
}
