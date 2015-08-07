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

package com.facebook.buck.intellij.plugin.format;

import com.facebook.buck.intellij.plugin.file.BuckFileUtil;
import com.facebook.buck.intellij.plugin.lang.BuckLanguage;
import com.intellij.application.options.IndentOptionsEditor;
import com.intellij.application.options.SmartIndentOptionsEditor;
import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;

/**
 * BUCK code style settings.
 */
public class BuckLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

  @Override
  public Language getLanguage() {
    return BuckLanguage.INSTANCE;
  }

  @Override
  public IndentOptionsEditor getIndentOptionsEditor() {
    return new SmartIndentOptionsEditor();
  }

  @Override
  public CommonCodeStyleSettings getDefaultCommonSettings() {
    CommonCodeStyleSettings defaultSettings = new CommonCodeStyleSettings(BuckLanguage.INSTANCE);
    CommonCodeStyleSettings.IndentOptions indentOptions = defaultSettings.initIndentOptions();

    indentOptions.INDENT_SIZE = 2;
    indentOptions.TAB_SIZE = 2;
    indentOptions.CONTINUATION_INDENT_SIZE = 2;

    defaultSettings.ALIGN_MULTILINE_PARAMETERS_IN_CALLS = true;
    defaultSettings.KEEP_BLANK_LINES_IN_DECLARATIONS = 1;
    defaultSettings.KEEP_BLANK_LINES_IN_CODE = 1;
    defaultSettings.RIGHT_MARGIN = 100;
    return defaultSettings;
  }

  @Override
  public String getCodeSample(SettingsType settingsType) {
    return BuckFileUtil.getSampleBuckFile();
  }
}
