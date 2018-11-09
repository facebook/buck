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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.intellij.execution.configuration.ConfigurationFactoryEx;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

public class TestConfigurationType implements ConfigurationType {
  public static TestConfigurationType getInstance() {
    return ConfigurationTypeUtil.findConfigurationType(TestConfigurationType.class);
  }

  public static final String ID = "Buck test";

  private final ConfigurationFactoryEx myFactory;

  public TestConfigurationType() {
    myFactory =
        new ConfigurationFactoryEx(this) {
          @Override
          @NotNull
          public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new TestConfiguration(project, this, "");
          }
        };
  }

  @Override
  public String getDisplayName() {
    return "Buck test";
  }

  @Override
  public String getConfigurationTypeDescription() {
    return "Buck test";
  }

  @Override
  public Icon getIcon() {
    return BuckIcons.CONFIGURATION_TEST;
  }

  @NotNull
  @Override
  public String getId() {
    return ID;
  }

  @Override
  public ConfigurationFactory[] getConfigurationFactories() {
    return new ConfigurationFactory[] {myFactory};
  }
}
