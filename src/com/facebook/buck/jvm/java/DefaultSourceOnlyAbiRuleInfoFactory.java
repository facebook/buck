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

package com.facebook.buck.jvm.java;

import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfoFactory;
import javax.tools.JavaFileManager;

/** Default factory for SourceOnlyAbiRuleInfos. */
class DefaultSourceOnlyAbiRuleInfoFactory implements SourceOnlyAbiRuleInfoFactory {
  private final DefaultSourceOnlyAbiRuleInfo ruleInfo;

  public DefaultSourceOnlyAbiRuleInfoFactory(DefaultSourceOnlyAbiRuleInfo ruleInfo) {
    this.ruleInfo = ruleInfo;
  }

  @Override
  public SourceOnlyAbiRuleInfo create(JavaFileManager fileManager) {
    ruleInfo.setFileManager(fileManager);
    return ruleInfo;
  }
}
