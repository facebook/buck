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

package com.facebook.buck.remoteexecution.util;

import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class RemoteExecutionUtilTest {
  private static final ImmutableSet<String> PROJECT_WHITELIST =
      ImmutableSet.of("//projectOne", "//projectTwo");

  private static final String PROJECT_ONE_PREFIX = "//projectOne";

  private static final String PROJECT_ONE_LIB_ONE = "//projectOne:libOne";
  private static final String PROJECT_ONE_LIB_TWO = "//projectOne/subdir:libOne";
  private static final String PROJECT_TWO_LIB_ONE = "//projectTwo:libOne";

  // Example target that is not enabled for Remote Execution
  private static final String PROJECT_THREE_LIB_ONE = "//projectThree:libOne";

  @Test
  public void testGetCommonTargetPrefixWithTargetsFromSameProjectReturnsProjectPrefix() {
    Optional<String> commonTargetPrefix =
        RemoteExecutionUtil.getCommonProjectPrefix(
            ImmutableSet.of(PROJECT_ONE_LIB_ONE, PROJECT_ONE_LIB_TWO));
    Assert.assertTrue(commonTargetPrefix.isPresent());
    Assert.assertEquals(PROJECT_ONE_PREFIX, commonTargetPrefix.get());
  }

  @Test
  public void testGetCommonTargetPrefixWithTargetsFromDifferentProjectReturnsEmpty() {
    Optional<String> commonTargetPrefix =
        RemoteExecutionUtil.getCommonProjectPrefix(
            ImmutableSet.of(PROJECT_ONE_LIB_ONE, PROJECT_TWO_LIB_ONE));
    Assert.assertFalse(commonTargetPrefix.isPresent());
  }

  @Test
  public void testSingleTargetThatDoesNotMatchWhiteList() {
    Assert.assertFalse(
        RemoteExecutionUtil.doTargetsMatchProjectWhitelist(
            ImmutableSet.of(PROJECT_THREE_LIB_ONE), PROJECT_WHITELIST));
  }

  @Test
  public void testSingleTargetThatMatchesWhiteList() {
    Assert.assertTrue(
        RemoteExecutionUtil.doTargetsMatchProjectWhitelist(
            ImmutableSet.of(PROJECT_TWO_LIB_ONE), PROJECT_WHITELIST));
  }

  @Test
  public void testMultiTargetsSomeMatchingSomeMismatching() {
    Assert.assertFalse(
        RemoteExecutionUtil.doTargetsMatchProjectWhitelist(
            ImmutableSet.of(PROJECT_ONE_LIB_ONE, PROJECT_THREE_LIB_ONE), PROJECT_WHITELIST));
  }

  @Test
  public void testMultipleMatchingTargetsFromDifferentEnabledProjects() {
    Assert.assertTrue(
        RemoteExecutionUtil.doTargetsMatchProjectWhitelist(
            ImmutableSet.of(PROJECT_ONE_LIB_ONE, PROJECT_TWO_LIB_ONE), PROJECT_WHITELIST));
  }

  @Test
  public void testMultiplTargetsWhereOneMatchesAndOneDoesNotMatch() {
    Assert.assertFalse(
        RemoteExecutionUtil.doTargetsMatchProjectWhitelist(
            ImmutableSet.of(PROJECT_ONE_LIB_ONE, PROJECT_THREE_LIB_ONE), PROJECT_WHITELIST));
  }

  @Test
  public void testMultipleMatchingTargetsFromSameProjects() {
    Assert.assertTrue(
        RemoteExecutionUtil.doTargetsMatchProjectWhitelist(
            ImmutableSet.of(PROJECT_ONE_LIB_ONE, PROJECT_ONE_LIB_TWO), PROJECT_WHITELIST));
  }

  @Test
  public void testWithEmptyTargetList() {
    Assert.assertFalse(
        RemoteExecutionUtil.doTargetsMatchProjectWhitelist(ImmutableSet.of(), PROJECT_WHITELIST));
  }

  @Test
  public void testWithEmptyProjectWhiteList() {
    Assert.assertFalse(
        RemoteExecutionUtil.doTargetsMatchProjectWhitelist(
            ImmutableSet.of(PROJECT_TWO_LIB_ONE), ImmutableSet.of()));
  }
}
