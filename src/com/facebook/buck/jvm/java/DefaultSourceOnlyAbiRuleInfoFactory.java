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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.jvm.core.BaseJavaAbiInfo;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfoFactory;
import com.google.common.collect.ImmutableList;
import javax.tools.JavaFileManager;

/** Default factory for SourceOnlyAbiRuleInfos. */
@BuckStyleValue
abstract class DefaultSourceOnlyAbiRuleInfoFactory implements SourceOnlyAbiRuleInfoFactory {

  abstract ImmutableList<BaseJavaAbiInfo> getFullJarInfos();

  abstract ImmutableList<BaseJavaAbiInfo> getAbiJarInfos();

  abstract String getFullyQualifiedBuildTargetName();

  abstract boolean isRuleIsRequiredForSourceOnlyAbi();

  public static DefaultSourceOnlyAbiRuleInfoFactory of(
      ImmutableList<BaseJavaAbiInfo> fullJarInfos,
      ImmutableList<BaseJavaAbiInfo> abiJarInfos,
      String fullyQualifiedBuildTargetName,
      boolean ruleIsRequiredForSourceOnlyAbi) {
    return ImmutableDefaultSourceOnlyAbiRuleInfoFactory.ofImpl(
        fullJarInfos, abiJarInfos, fullyQualifiedBuildTargetName, ruleIsRequiredForSourceOnlyAbi);
  }

  @Override
  public SourceOnlyAbiRuleInfo create(JavaFileManager fileManager) {
    return new DefaultSourceOnlyAbiRuleInfo(
        getFullJarInfos(),
        getAbiJarInfos(),
        fileManager,
        getFullyQualifiedBuildTargetName(),
        isRuleIsRequiredForSourceOnlyAbi());
  }
}
