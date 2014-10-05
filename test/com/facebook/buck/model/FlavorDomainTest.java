/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

public class FlavorDomainTest {

  @Test
  public void getFlavor() throws FlavorDomainException {
    Flavor flavor = new Flavor("hello");
    FlavorDomain<String> domain = new FlavorDomain<>(
        "test",
        ImmutableMap.of(flavor, "something"));
    BuildTarget target = BuildTargetFactory.newInstance("//:test#hello");
    assertEquals(Optional.of(flavor), domain.getFlavor(target.getFlavors()));
    target = BuildTargetFactory.newInstance("//:test#invalid");
    assertEquals(Optional.<Flavor>absent(), domain.getFlavor(target.getFlavors()));
  }

  @Test
  public void multipleFlavorsForSameDomainShouldThrow() {
    Flavor hello = new Flavor("hello");
    Flavor goodbye = new Flavor("goodbye");
    FlavorDomain<String> domain = new FlavorDomain<>(
        "test",
        ImmutableMap.of(
            hello, "something",
            goodbye, "something"));
    BuildTarget target = BuildTargets.extendFlavoredBuildTarget(
        BuildTargetFactory.newInstance("//:test"),
        ImmutableSet.of(hello, goodbye));
    try {
      domain.getFlavor(target.getFlavors());
      fail("should have thrown");
    } catch (FlavorDomainException e) {
      assertTrue(e.getMessage().contains("multiple \"test\" flavors"));
    }
  }

  @Test
  public void getValue() throws FlavorDomainException {
    Flavor flavor = new Flavor("hello");
    FlavorDomain<String> domain = new FlavorDomain<>(
        "test",
        ImmutableMap.of(flavor, "something"));
    String val = domain.getValue(flavor);
    assertEquals("something", val);
    try {
      domain.getValue(new Flavor("invalid"));
      fail("should have thrown");
    } catch (FlavorDomainException e) {
      assertTrue(e.getMessage().contains("has no flavor"));
    }
  }

}
