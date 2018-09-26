/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.autodeps;

import org.junit.Assert;
import org.junit.Test;

public class BuckTargetTest {

  @Test
  public void parseFullyQualifiedTarget() {
    BuckTarget target = BuckTarget.parse("cell//path/to/module:rule");
    Assert.assertEquals("cell", target.getCellName());
    Assert.assertEquals("path/to/module", target.getRelativePath());
    Assert.assertEquals("rule", target.getRuleName());
    Assert.assertEquals("cell//path/to/module:rule", target.fullyQualified());
  }

  @Test
  public void parseEmptyCell() {
    BuckTarget target = BuckTarget.parse("//path/to/module:rule");
    Assert.assertEquals("", target.getCellName());
    Assert.assertEquals("path/to/module", target.getRelativePath());
    Assert.assertEquals("rule", target.getRuleName());
    Assert.assertEquals("//path/to/module:rule", target.fullyQualified());
  }

  @Test
  public void parseEmptyPath() {
    BuckTarget target = BuckTarget.parse("cell//:rule");
    Assert.assertEquals("cell", target.getCellName());
    Assert.assertEquals("", target.getRelativePath());
    Assert.assertEquals("rule", target.getRuleName());
    Assert.assertEquals("cell//:rule", target.fullyQualified());
  }

  @Test
  public void parseEmptyRule() {
    BuckTarget target = BuckTarget.parse("cell//path/to/module");
    Assert.assertEquals("cell", target.getCellName());
    Assert.assertEquals("path/to/module", target.getRelativePath());
    Assert.assertEquals("module", target.getRuleName());
    Assert.assertEquals("cell//path/to/module:module", target.fullyQualified());
  }

  @Test
  public void parseOnlyRule() {
    BuckTarget target = BuckTarget.parse(":rule");
    Assert.assertEquals("", target.getCellName());
    Assert.assertEquals("", target.getRelativePath());
    Assert.assertEquals("rule", target.getRuleName());
    Assert.assertEquals("//:rule", target.fullyQualified());
  }

  @Test
  public void parseTargetWithSlashInRuleName() {
    // Unclear whether or not this is a requirement
    BuckTarget target = BuckTarget.parse("cell//path/to/module:rule/name");
    Assert.assertEquals("cell", target.getCellName());
    Assert.assertEquals("path/to/module", target.getRelativePath());
    Assert.assertEquals("rule/name", target.getRuleName());
    Assert.assertEquals("cell//path/to/module:rule/name", target.fullyQualified());
  }

  @Test
  public void parseRelativeToFullyQualifiedTarget() {
    BuckTarget base = BuckTarget.parse("cell//path/to/module:rule");
    BuckTarget other = base.parseRelative("foo//bar/baz:qux");
    Assert.assertEquals("foo//bar/baz:qux", other.fullyQualified());
  }

  @Test
  public void parseRelativeToTargetWithNoCell() {
    BuckTarget base = BuckTarget.parse("cell//path/to/module:rule");
    BuckTarget other = base.parseRelative("//bar/baz:qux");
    Assert.assertEquals("cell//bar/baz:qux", other.fullyQualified());
  }

  @Test
  public void parseRelativeToTargetWithNoCellOrPath() {
    BuckTarget base = BuckTarget.parse("cell//path/to/module:rule");
    BuckTarget other = base.parseRelative(":qux");
    Assert.assertEquals("cell//path/to/module:qux", other.fullyQualified());
  }

  @Test
  public void relativeToDifferentCell() {
    BuckTarget base = BuckTarget.parse("cell//path/to/module:rule");
    BuckTarget other = BuckTarget.parse("different//what:ever");
    Assert.assertEquals("cell//path/to/module:rule", base.relativeTo(other));
  }

  @Test
  public void relativeToSameCellDifferentPath() {
    BuckTarget base = BuckTarget.parse("cell//path/to/module:rule");
    BuckTarget other = BuckTarget.parse("cell//path/is/different:rule");
    Assert.assertEquals("//path/to/module:rule", base.relativeTo(other));
  }

  @Test
  public void relativeToSameCellSamePath() {
    BuckTarget base = BuckTarget.parse("cell//path/to/module:rule");
    BuckTarget other = BuckTarget.parse("cell//path/to/module:other");
    Assert.assertEquals(":rule", base.relativeTo(other));
  }
}
