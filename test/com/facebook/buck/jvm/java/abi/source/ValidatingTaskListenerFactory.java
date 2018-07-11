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
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTaskProxyImpl;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.sun.source.util.TaskListener;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileManager;

class ValidatingTaskListenerFactory implements CompilerTreeApiTest.TaskListenerFactory {
  private final String ruleName;
  private final Set<String> targetsAvailableForSoruceOnly = new HashSet<>();
  private boolean requiredForSourceAbi = false;

  ValidatingTaskListenerFactory(String ruleName) {
    this.ruleName = ruleName;
  }

  public void setRuleIsRequiredForSourceAbi(boolean value) {
    this.requiredForSourceAbi = value;
  }

  public void addTargetAvailableForSourceOnlyAbi(String target) {
    targetsAvailableForSoruceOnly.add(target);
  }

  @Override
  public TaskListener newTaskListener(BuckJavacTask task) {
    return new ValidatingTaskListener(
        new BuckJavacTaskProxyImpl(task),
        new SourceOnlyAbiRuleInfo() {
          @Override
          public String getRuleName() {
            return ruleName;
          }

          @Override
          public boolean ruleIsRequiredForSourceOnlyAbi() {
            return requiredForSourceAbi;
          }

          @Override
          public void setFileManager(JavaFileManager fileManager) {}

          @Override
          public boolean elementIsAvailableForSourceOnlyAbi(Elements elements, Element element) {
            if (targetsAvailableForSoruceOnly.contains(getOwningTarget(elements, element))) {
              return true;
            }
            return elements
                .getBinaryName(MoreElements.getTypeElement(element))
                .toString()
                .startsWith("java.");
          }

          @Override
          public String getOwningTarget(Elements elements, Element element) {
            PackageElement packageElement = MoreElements.getPackageElement(element);

            return "//"
                + packageElement.getQualifiedName().toString().replace('.', '/')
                + ":"
                + packageElement.getSimpleName();
          }
        },
        () -> false,
        Diagnostic.Kind.ERROR);
  }
}
