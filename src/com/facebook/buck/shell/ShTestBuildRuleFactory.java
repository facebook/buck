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

package com.facebook.buck.shell;

import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildRuleFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;

import java.nio.file.Path;

public class ShTestBuildRuleFactory extends AbstractBuildRuleFactory<ShTestRule.Builder> {

  @Override
  public ShTestRule.Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return ShTestRule.newShTestRuleBuilder(params);
  }

  @Override
  protected void amendBuilder(ShTestRule.Builder builder, BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {
    // test
    String test = params.getRequiredStringAttribute("test");
    Path testPath = params.resolveFilePathRelativeToBuildFileDirectory(test);
    builder.setTest(testPath);
  }

}
