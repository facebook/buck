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

package com.facebook.buck.rules.keys.hasher;

import static com.facebook.buck.log.thrift.rulekeys.Value._Fields.STRING_VALUE;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.type.RuleType;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.log.Logger;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.log.thrift.rulekeys.ByteArray;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.log.thrift.rulekeys.HashedPath;
import com.facebook.buck.log.thrift.rulekeys.Key;
import com.facebook.buck.log.thrift.rulekeys.NonHashedPath;
import com.facebook.buck.log.thrift.rulekeys.NullValue;
import com.facebook.buck.log.thrift.rulekeys.RuleKeyHash;
import com.facebook.buck.log.thrift.rulekeys.Sha1;
import com.facebook.buck.log.thrift.rulekeys.TargetPath;
import com.facebook.buck.log.thrift.rulekeys.Value;
import com.facebook.buck.rules.keys.SourceRoot;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Pattern;

/**
 * A rule key hasher that attempts to create thrift structures representing rule keys as hashing
 * occurs, and writes out the serialized data once the rule key is "complete"
 */
public class ThriftRuleKeyHasher implements RuleKeyHasher<FullRuleKey> {
  private final Logger LOG = Logger.get(ThriftRuleKeyHasher.class);

  private final Stack<Value> pendingValues;
  private final FullRuleKey ruleKey;
  private final ThriftRuleKeyLogger ruleKeyLogger;

  /**
   * Creates a {@link ThriftRuleKeyHasher}
   *
   * @param ruleKeyLogger The ruleKeyLogger used to write serialized thrift structures out
   */
  public ThriftRuleKeyHasher(ThriftRuleKeyLogger ruleKeyLogger) {
    pendingValues = new Stack<>();
    ruleKey = new FullRuleKey();
    ruleKey.values = new HashMap<>();
    this.ruleKeyLogger = ruleKeyLogger;
  }

  private ThriftRuleKeyHasher push(Value value) {
    pendingValues.push(value);
    return this;
  }

  private Value pop() {
    return pendingValues.pop();
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putKey(String key) {
    return push(Value.key(new Key(key)));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putNull() {
    return push(Value.nullValue(new NullValue()));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putCharacter(char val) {
    return push(Value.numberValue(val));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putBoolean(boolean val) {
    return push(Value.boolValue(val));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putNumber(Number val) {
    return push(Value.numberValue(val.doubleValue()));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putString(String val) {
    return push(Value.stringValue(val));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putBytes(byte[] bytes) {
    return push(Value.byteArray(new ByteArray(bytes.length)));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putPattern(Pattern pattern) {
    return push(
        Value.pattern(new com.facebook.buck.log.thrift.rulekeys.Pattern(pattern.toString())));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putSha1(Sha1HashCode sha1) {
    return push(Value.sha1Hash(new Sha1(sha1.toString())));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putPath(Path path, HashCode hash) {
    return push(Value.hashedPath(new HashedPath(path.toString(), hash.toString())));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putArchiveMemberPath(ArchiveMemberPath path, HashCode hash) {
    return push(
        Value.archiveMemberPath(
            new com.facebook.buck.log.thrift.rulekeys.ArchiveMemberPath(
                path.getArchivePath().toString(),
                path.getMemberPath().toString(),
                hash.toString())));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putNonHashingPath(String path) {
    return push(Value.path(new NonHashedPath(path)));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putSourceRoot(SourceRoot sourceRoot) {
    push(
        Value.sourceRoot(
            new com.facebook.buck.log.thrift.rulekeys.SourceRoot(sourceRoot.getName())));
    return this;
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putRuleKey(RuleKey ruleKey) {
    return push(Value.ruleKeyHash(new RuleKeyHash(ruleKey.toString())));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putRuleType(RuleType ruleType) {
    push(
        Value.buildRuleType(
            new com.facebook.buck.log.thrift.rulekeys.BuildRuleType(ruleType.getName())));
    return this;
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putBuildTarget(BuildTarget buildTarget) {
    return push(
        Value.buildTarget(
            new com.facebook.buck.log.thrift.rulekeys.BuildTarget(
                buildTarget.getFullyQualifiedName())));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putBuildTargetSourcePath(
      BuildTargetSourcePath buildTargetSourcePath) {
    return push(Value.targetPath(new TargetPath(buildTargetSourcePath.representationForRuleKey())));
  }

  /**
   * Adds a container to the stack. This will pop down the stack of already hashed values in order
   * to populate the container. The container Value is then put back on the stack
   *
   * @param container The container type
   * @param length The number of elements in the container. This can vary slightly by container type
   * @return The original RuleKeyHasher object
   */
  @Override
  public RuleKeyHasher<FullRuleKey> putContainer(Container container, int length) {
    try {
      if (container == Container.LIST) {
        return putList(length);
      } else if (container == Container.MAP) {
        return putMap(length);
      } else if (container == Container.TUPLE) {
        // Tuples can either be key value pairs, or just a list of objects. We have to
        // peek at the top element in the stack, and if it's a key, then we have a key
        // value pair, and we'll stick that in the map. If we don't have a key, it
        // goes into the list container
        Value topValue = this.pendingValues.peek();
        if (topValue.getSetField() == Value._Fields.KEY) {
          return putTupleMap(length);
        } else {
          return putList(length);
        }
      }
      return this;
    } catch (Exception e) {
      LOG.warn(
          e,
          "Got exception in putContainer %s type: %s len: %s. Rulekey so far: %s",
          this.hashCode(),
          container,
          length,
          ruleKey);
      return this;
    }
  }

  private ThriftRuleKeyHasher putList(int length) {
    List<Value> thriftContainer = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      thriftContainer.add(pop());
    }
    return push(Value.containerList(thriftContainer));
  }

  private ThriftRuleKeyHasher putMap(int length) {
    Map<String, Value> thriftContainer = new HashMap<>(length);
    // Length for maps is the number of keys + number of values, unlike
    // tuple or list
    for (int i = 0; i < length; i += 2) {
      // Normally key value pairs are pushed on the stack in the order
      // VALUE, KEY. Maps seem to push in the order KEY, VALUE, so pop
      // the value first, then determine the key type and get the key.s
      Value value = pop();
      Value key = pop();
      if (key.getSetField() == STRING_VALUE) {
        thriftContainer.put(key.getStringValue(), value);
      } else if (key.getSetField() == Value._Fields.KEY) {
        thriftContainer.put(key.getKey().key, value);
      } else if (key.getSetField() == Value._Fields.TARGET_PATH) {
        thriftContainer.put(key.getTargetPath().path, value);
      }
    }
    return push(Value.containerMap(thriftContainer));
  }

  private ThriftRuleKeyHasher putTupleMap(int length) {
    // Tuple maps are represented on the stack by VALUE, KEY, so they're pretty
    // simple to handle
    Map<String, Value> thriftContainer = new HashMap<>(length);
    for (int i = 0; i < length; i++) {
      Value key = pop();
      Value value = pop();
      thriftContainer.put(key.getKey().key, value);
    }
    return push(Value.containerMap(thriftContainer));
  }

  @Override
  public RuleKeyHasher<FullRuleKey> putWrapper(Wrapper wrapper) {
    try {
      push(
          Value.wrapper(
              new com.facebook.buck.log.thrift.rulekeys.Wrapper(wrapper.toString(), pop())));
    } catch (EmptyStackException e) {
      LOG.warn(
          e,
          "Got exception in putWrapper %s type: %s. Rulekey so far: %s",
          this.hashCode(),
          wrapper,
          ruleKey);
    }
    return this;
  }

  /**
   * Finishes populating the ruleKey with remaining key/value pairs and returns it
   *
   * @return The fully populated ruleKey
   */
  @Override
  public FullRuleKey hash() {
    consumeAllPending();
    return ruleKey;
  }

  /**
   * Pops all remaining key/value pairs from the stack, populating the values() map. Also sets the
   * name and type fields if the right keys were specified in the values map.
   */
  private void consumeAllPending() {
    try {
      while (!pendingValues.empty()) {
        String key = pop().getKey().key;
        Value value = pop();
        ruleKey.values.put(key, value);
      }
    } catch (Exception e) {
      LOG.warn(
          e,
          "Got exception in consumeAllPending %s. Key so far: %s",
          this.hashCode(),
          this.ruleKey);
    }
    if (ruleKey.name == null) {
      ruleKey.name = getStringValueFromProperty("", ".target_name", ".name");
    }
    if (ruleKey.type == null) {
      ruleKey.type = getStringValueFromProperty("UNKNOWN", ".rule_key_type", ".type");
    }
  }

  /**
   * Try to grab a string value from the values property of the current rule key
   *
   * @param defaultValue Value to return if non of the properties could be found
   * @param fields List of fields to look for (in order of preference)
   * @return The first valid value that is found, or the default value if none is found
   */
  private String getStringValueFromProperty(String defaultValue, String... fields) {
    for (String field : fields) {
      Value potentialValue = ruleKey.values.get(field);
      if (potentialValue != null && potentialValue.getSetField() == STRING_VALUE) {
        return potentialValue.getStringValue();
      }
    }
    return defaultValue;
  }

  /**
   * Sets the hash key on the ruleKey object
   *
   * @param code The hash code to use
   */
  public void setHashKey(HashCode code) {
    ruleKey.key = code.toString();
  }

  /** Writes the object out to the logger */
  public void flushToLogger() {
    ruleKeyLogger.write(ruleKey);
  }
}
