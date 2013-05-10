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

import com.facebook.buck.step.Verbosity;

import org.easymock.EasyMockSupport;
import org.junit.Test;

/** Unit test for {@link AbstractCommandOptions}. */
public class AbstractCommandOptionsTest extends EasyMockSupport {

  @Test
  public void testVerbosity() {
    BuckConfig buckConfig = createMock(BuckConfig.class);
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};

    assertSame("The default value should be STANDARD_INFORMATION.",
        Verbosity.STANDARD_INFORMATION, options.getVerbosity());

    options.setVerbosity(Verbosity.COMMANDS_AND_OUTPUT);
    assertSame("Setting the Verbosity via the setter should override the default value.",
        Verbosity.COMMANDS_AND_OUTPUT, options.getVerbosity());

    options.setVerbosity(null);
    assertSame(
        "Setting the Verbosity to null should restore the default value.",
        Verbosity.STANDARD_INFORMATION, options.getVerbosity());
  }

}
