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

import com.facebook.buck.intellij.plugin.file.BuckFileUtil;
import com.facebook.buck.intellij.plugin.highlight.BuckSyntaxHighlighter;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import icons.BuckIcons;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Map;

public class BuckColorSettingsPage implements ColorSettingsPage {

  private static final AttributesDescriptor[] DESCRIPTORS = new AttributesDescriptor[]{
      new AttributesDescriptor("Key", BuckSyntaxHighlighter.BUCK_KEYWORD),
      new AttributesDescriptor("String", BuckSyntaxHighlighter.BUCK_STRING),
      new AttributesDescriptor("Comment", BuckSyntaxHighlighter.BUCK_COMMENT),
      new AttributesDescriptor("Name", BuckSyntaxHighlighter.BUCK_RULE_NAME),
  };

  @Override
  public Icon getIcon() {
    return BuckIcons.FILE_TYPE;
  }

  @Override
  public SyntaxHighlighter getHighlighter() {
    return new BuckSyntaxHighlighter();
  }

  @Override
  public String getDemoText() {
    return BuckFileUtil.getSampleBuckFile();
  }

  @Nullable
  @Override
  public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
    return null;
  }

  @Override
  public AttributesDescriptor[] getAttributeDescriptors() {
    return DESCRIPTORS;
  }

  @Override
  public ColorDescriptor[] getColorDescriptors() {
    return ColorDescriptor.EMPTY_ARRAY;
  }

  @Override
  public String getDisplayName() {
    return "Buck";
  }
}
