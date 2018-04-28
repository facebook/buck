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

package com.facebook.buck.core.toolchain.toolprovider;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.BuildRuleResolver;

public interface ToolProvider {

  /** @return the provided {@link Tool} object. */
  Tool resolve(BuildRuleResolver resolver);

  /** @return any dependencies required at parse time to support the provided tool. */
  Iterable<BuildTarget> getParseTimeDeps();
}
