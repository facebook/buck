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
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * RuleKeyLogger that writes to the buck-out log.
 */
public class DefaultRuleKeyLogger implements RuleKeyLogger {

  private static final Logger logger = Logger.get(RuleKeyBuilder.class);
  private static final Scope NO_OP_SCOPE = new Scope() {
    @Override
    public void close() {
      // Intentional no-op (duh!).
    }
  };

  private List<String> logElms;
  @Nullable
  private String currentKey;
  private boolean currentKeyHasValue;

  public DefaultRuleKeyLogger() {
    this.logElms = new ArrayList<>();
    this.currentKey = null;
  }

  @Override
  public void registerRuleKey(RuleKey ruleKey) {
    logger.verbose("RuleKey %s=%s", ruleKey, Joiner.on("").join(logElms));
  }

  @Override
  public Scope pushKey(String key) {
    final String previousKey = currentKey;
    final boolean currentKeyHadValue = currentKeyHasValue;
    currentKey = key;
    currentKeyHasValue = false;

    return new Scope() {
      @Override
      public void close() {
        if (currentKeyHasValue) {
          appendLogElement(String.format("key(%s):", currentKey));
        }
        currentKey = previousKey;
        currentKeyHasValue = currentKeyHadValue;
      }
    };
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
  public void addArchiveMemberPath(
      ArchiveMemberPath archiveMemberPath, HashCode hashCode) {
    appendLogElement(String.format("archiveMember(%s:%s):", archiveMemberPath, hashCode));
  }

  @Override
  public void addPath(Path path, HashCode hashCode) {
    appendLogElement(String.format("path(%s:%s):", path, hashCode));
  }

  @Override
  public void addNonHashingPath(String path) {
    appendLogElement(String.format("path(%s):", path));
  }

  @Override
  public void addNullValue() {
    // No-op.
  }

  @Override
  public void addValue(Enum<?> value) {
    // No-op.
  }

  @Override
  public void addValue(String value) {
    appendLogElement(String.format("string(\"%s\"):", value));
  }

  @Override
  public void addValue(boolean value) {
    appendLogElement(String.format("boolean(\"%s\"):", value ? "true" : "false"));
  }

  @Override
  public void addValue(int value) {
    appendLogElement(String.format("number(%s):", value));
  }

  @Override
  public void addValue(double value) {
    appendLogElement(String.format("number(%s):", value));
  }

  @Override
  public void addValue(float value) {
    appendLogElement(String.format("number(%s):", value));
  }

  @Override
  public void addValue(long value) {
    appendLogElement(String.format("number(%s):", value));
  }

  @Override
  public void addValue(short value) {
    appendLogElement(String.format("number(%s):", value));
  }

  @Override
  public void addValue(BuildRuleType value) {
    appendLogElement(String.format("ruleKeyType(%s):", value));
  }

  @Override
  public void addValue(RuleKey value) {
    appendLogElement(String.format("ruleKey(sha1=%s):", value));
  }

  @Override
  public void addValue(BuildTarget value) {
    appendLogElement(String.format("target(%s):", value));
  }

  @Override
  public void addValue(SourceRoot value) {
    appendLogElement(String.format("sourceroot(%s):", value));
  }

  @Override
  public Scope pushSourceWithFlags() {
    return NO_OP_SCOPE;
  }

  @Override
  public void addValue(byte[] value) {
    appendLogElement(String.format("byteArray(%s):", value));
  }

  private void appendLogElement(String value) {
    logElms.add(value);
    currentKeyHasValue = true;
  }

  @VisibleForTesting
  ImmutableList<String> getCurrentLogElements() {
    return ImmutableList.copyOf(logElms);
  }
}
