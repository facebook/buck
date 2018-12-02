/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableSortedSet;

public abstract class AbstractXcodeScriptBuilder<
        T extends AbstractXcodeScriptBuilder<T, U>,
        U extends DescriptionWithTargetGraph<XcodeScriptDescriptionArg>>
    extends AbstractNodeBuilder<
        XcodeScriptDescriptionArg.Builder, XcodeScriptDescriptionArg, U, BuildRule> {

  public AbstractXcodeScriptBuilder(U description, BuildTarget target) {
    super(description, target);
  }

  public T setSrcs(ImmutableSortedSet<SourcePath> srcs) {
    getArgForPopulating().setSrcs(srcs);
    return getThis();
  }

  public T setInputs(ImmutableSortedSet<String> inputs) {
    getArgForPopulating().setInputs(inputs);
    return getThis();
  }

  public T setInputFileLists(ImmutableSortedSet<String> inputFileLists) {
    getArgForPopulating().setInputFileLists(inputFileLists);
    return getThis();
  }

  public T setOutputs(ImmutableSortedSet<String> outputs) {
    getArgForPopulating().setOutputs(outputs);
    return getThis();
  }

  public T setOutputFileLists(ImmutableSortedSet<String> outputFileLists) {
    getArgForPopulating().setOutputFileLists(outputFileLists);
    return getThis();
  }

  public T setCmd(String cmd) {
    getArgForPopulating().setCmd(cmd);
    return getThis();
  }

  protected abstract T getThis();
}
