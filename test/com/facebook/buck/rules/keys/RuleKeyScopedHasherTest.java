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

package com.facebook.buck.rules.keys;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.rules.keys.hasher.CountingRuleKeyHasher;
import com.facebook.buck.rules.keys.hasher.GuavaRuleKeyHasher;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.Scope;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import org.junit.Test;

public class RuleKeyScopedHasherTest {

  @Test
  public void testKeyScope() {
    CountingRuleKeyHasher<HashCode> countHasher = newCountHasher();
    RuleKeyScopedHasher containerHasher = new DefaultRuleKeyScopedHasher<>(countHasher);
    try (Scope keyScope = containerHasher.keyScope("key")) {
      countHasher.putString("val");
    }
    assertEquals(newGuavaHasher().putString("val").putKey("key").hash(), countHasher.hash());
  }

  @Test
  public void testKeyScopeNoOp() {
    CountingRuleKeyHasher<HashCode> countHasher = newCountHasher();
    RuleKeyScopedHasher containerHasher = new DefaultRuleKeyScopedHasher<>(countHasher);
    try (Scope keyScope = containerHasher.keyScope("key")) { // NOPMD
      // no-op
    }
    assertEquals(newGuavaHasher().hash(), countHasher.hash());
  }

  @Test
  public void testWrapperScope() {
    testWrapperScopeAndNoOp(RuleKeyHasher.Wrapper.SUPPLIER);
    testWrapperScopeAndNoOp(RuleKeyHasher.Wrapper.OPTIONAL);
    testWrapperScopeAndNoOp(RuleKeyHasher.Wrapper.EITHER_LEFT);
    testWrapperScopeAndNoOp(RuleKeyHasher.Wrapper.EITHER_RIGHT);
    testWrapperScopeAndNoOp(RuleKeyHasher.Wrapper.BUILD_RULE);
    testWrapperScopeAndNoOp(RuleKeyHasher.Wrapper.APPENDABLE);
  }

  private void testWrapperScopeAndNoOp(RuleKeyHasher.Wrapper wrapper) {
    testWrapperScope(wrapper);
    testWrapperScopeNoOp(wrapper);
  }

  private void testWrapperScope(RuleKeyHasher.Wrapper wrapper) {
    CountingRuleKeyHasher<HashCode> countHasher = newCountHasher();
    RuleKeyScopedHasher containerHasher = new DefaultRuleKeyScopedHasher<>(countHasher);
    try (Scope wrapperScope = containerHasher.wrapperScope(wrapper)) {
      countHasher.putString("val");
    }
    assertEquals(newGuavaHasher().putString("val").putWrapper(wrapper).hash(), countHasher.hash());
  }

  private void testWrapperScopeNoOp(RuleKeyHasher.Wrapper wrapper) {
    CountingRuleKeyHasher<HashCode> countHasher = newCountHasher();
    RuleKeyScopedHasher containerHasher = new DefaultRuleKeyScopedHasher<>(countHasher);
    try (Scope wrapperScope = containerHasher.wrapperScope(wrapper)) { // NOPMD
      // no-op
    }
    assertEquals(newGuavaHasher().hash(), countHasher.hash());
  }

  @Test
  public void testContainerScopeList() {
    CountingRuleKeyHasher<HashCode> countHasher = newCountHasher();
    RuleKeyScopedHasher containerHasher = new DefaultRuleKeyScopedHasher<>(countHasher);
    try (RuleKeyScopedHasher.ContainerScope containerScope =
        containerHasher.containerScope(RuleKeyHasher.Container.LIST)) {
      try (Scope elementScope = containerScope.elementScope()) {
        countHasher.putString("val1");
      }
      try (Scope elementScope = containerScope.elementScope()) {
        countHasher.putString("val2");
      }
      try (Scope elementScope = containerScope.elementScope()) {
        countHasher.putString("val3");
      }
    }
    assertEquals(
        newGuavaHasher()
            .putString("val1")
            .putString("val2")
            .putString("val3")
            .putContainer(RuleKeyHasher.Container.LIST, 3)
            .hash(),
        countHasher.hash());
  }

  @Test
  public void testContainerScopeListNoOp() {
    CountingRuleKeyHasher<HashCode> countHasher = newCountHasher();
    RuleKeyScopedHasher containerHasher = new DefaultRuleKeyScopedHasher<>(countHasher);
    try (RuleKeyScopedHasher.ContainerScope containerScope =
        containerHasher.containerScope(RuleKeyHasher.Container.LIST)) { // NOPMD
      // no-op
    }
    assertEquals(newGuavaHasher().hash(), countHasher.hash());
  }

  @Test
  public void testContainerScopeMap() {
    CountingRuleKeyHasher<HashCode> countHasher = newCountHasher();
    RuleKeyScopedHasher containerHasher = new DefaultRuleKeyScopedHasher<>(countHasher);
    try (RuleKeyScopedHasher.ContainerScope containerScope =
        containerHasher.containerScope(RuleKeyHasher.Container.MAP)) {
      try (Scope elementScope = containerScope.elementScope()) {
        countHasher.putString("key1");
      }
      try (Scope elementScope = containerScope.elementScope()) {
        countHasher.putString("val1");
      }
      try (Scope elementScope = containerScope.elementScope()) {
        countHasher.putString("key2");
      }
      try (Scope elementScope = containerScope.elementScope()) {
        countHasher.putString("val2");
      }
    }
    assertEquals(
        newGuavaHasher()
            .putString("key1")
            .putString("val1")
            .putString("key2")
            .putString("val2")
            .putContainer(RuleKeyHasher.Container.MAP, 4)
            .hash(),
        countHasher.hash());
  }

  @Test
  public void testContainerScopeMapNoOp() {
    CountingRuleKeyHasher<HashCode> countHasher = newCountHasher();
    RuleKeyScopedHasher containerHasher = new DefaultRuleKeyScopedHasher<>(countHasher);
    try (RuleKeyScopedHasher.ContainerScope containerScope =
        containerHasher.containerScope(RuleKeyHasher.Container.MAP)) { // NOPMD
      // no-op
    }
    assertEquals(newGuavaHasher().hash(), countHasher.hash());
  }

  private CountingRuleKeyHasher<HashCode> newCountHasher() {
    return new CountingRuleKeyHasher<>(newGuavaHasher());
  }

  private GuavaRuleKeyHasher newGuavaHasher() {
    return new GuavaRuleKeyHasher(Hashing.sha1().newHasher());
  }
}
