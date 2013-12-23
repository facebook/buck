/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertSame;

import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Unit test for {@link AbstractCommandOptions}. */
public class AbstractCommandOptionsTest extends EasyMockSupport {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testConstructor() {
    BuckConfig buckConfig = createMock(BuckConfig.class);
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};

    assertSame(buckConfig, options.getBuckConfig());
  }
}
