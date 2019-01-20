/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfoFactory.SourceOnlyAbiRuleInfo;
import com.facebook.buck.util.liteinfersupport.Nullable;
import com.sun.source.util.Trees;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/** Simulates the behavior of {@code javac}'s file manager for source-only ABI classpaths. */
public class FileManagerSimulator {
  private final Elements elements;
  private final Trees trees;
  private final SourceOnlyAbiRuleInfo ruleInfo;

  public FileManagerSimulator(Elements elements, Trees trees, SourceOnlyAbiRuleInfo ruleInfo) {
    this.elements = elements;
    this.trees = trees;
    this.ruleInfo = ruleInfo;
  }

  @Nullable
  public String getOwningTarget(Element element) {
    return ruleInfo.getOwningTarget(elements, element);
  }

  public boolean typeWillBeAvailable(TypeElement type) {
    return isCompiledInCurrentRun(type)
        || ruleInfo.elementIsAvailableForSourceOnlyAbi(elements, type);
  }

  public boolean isCompiledInCurrentRun(TypeElement typeElement) {
    return trees.getTree(typeElement) != null;
  }
}
