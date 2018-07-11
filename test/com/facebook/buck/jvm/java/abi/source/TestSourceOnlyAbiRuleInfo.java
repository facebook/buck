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
import com.facebook.buck.jvm.java.lang.model.MoreElements;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileManager;

public class TestSourceOnlyAbiRuleInfo implements SourceOnlyAbiRuleInfo {
  private final String ruleName;
  private boolean requiredForSourceOnlyAbi = false;
  private Map<String, String> owningRules = new HashMap<>();
  private Set<String> rulesAvailableForSourceOnlyAbi = new HashSet<>();

  public TestSourceOnlyAbiRuleInfo(String ruleName) {
    this.ruleName = ruleName;
  }

  public TestSourceOnlyAbiRuleInfo addElementOwner(String qualifiedName, String owningRule) {
    owningRules.put(qualifiedName, owningRule);
    return this;
  }

  public TestSourceOnlyAbiRuleInfo addAvailableRule(String ruleName) {
    rulesAvailableForSourceOnlyAbi.add(ruleName);
    return this;
  }

  @Override
  public void setFileManager(JavaFileManager fileManager) {}

  @Override
  public boolean elementIsAvailableForSourceOnlyAbi(Elements elements, Element element) {
    if (MoreElements.getPackageElement(element).getQualifiedName().contentEquals("java.lang")) {
      return true;
    }
    return rulesAvailableForSourceOnlyAbi.contains(getOwningTarget(elements, element));
  }

  @Override
  public String getOwningTarget(Elements elements, Element element) {
    TypeElement typeElement = MoreElements.getTopLevelTypeElement(element);
    return owningRules.get(typeElement.getQualifiedName().toString());
  }

  @Override
  public String getRuleName() {
    return ruleName;
  }

  @Override
  public boolean ruleIsRequiredForSourceOnlyAbi() {
    return requiredForSourceOnlyAbi;
  }
}
