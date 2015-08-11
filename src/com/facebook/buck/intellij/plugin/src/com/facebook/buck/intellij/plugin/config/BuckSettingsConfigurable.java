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

package com.facebook.buck.intellij.plugin.config;

import com.facebook.buck.intellij.plugin.ui.BuckSettingsUI;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;

import javax.swing.JComponent;

public class BuckSettingsConfigurable implements SearchableConfigurable {

  private BuckSettingsUI panel;

  public BuckSettingsConfigurable() {
  }

  @Override
  public String getId() {
    return getHelpTopic();
  }

  @Override
  public Runnable enableSearch(String option) {
    return null;
  }

  @Override
  public String getDisplayName() {
    return "Buck";
  }

  @Override
  public String getHelpTopic() {
    return "buck.settings";
  }

  @Override
  public JComponent createComponent() {
    panel = new BuckSettingsUI();
    return panel;
  }

  @Override
  public boolean isModified() {
    return panel != null && panel.isModified();
  }

  @Override
  public void apply() throws ConfigurationException {
    if (panel != null) {
      panel.apply();
    }
  }

  @Override
  public void reset() {
    if (panel != null) {
      panel.reset();
    }
  }

  @Override
  public void disposeUIResources() {
    panel = null;
  }
}

