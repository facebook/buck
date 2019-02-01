/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.intellij.ideabuck.config;

import com.facebook.buck.intellij.ideabuck.highlight.BcfgSyntaxHighlighter;
import com.facebook.buck.intellij.ideabuck.lang.BcfgFileType;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.*;
import java.util.Map;
import javax.swing.*;
import org.jetbrains.annotations.*;

/** Color settings for {@code .buckconfig} files. */
public class BcfgColorSettingsPage implements ColorSettingsPage {
  private static final AttributesDescriptor[] DESCRIPTORS =
      new AttributesDescriptor[] {
        new AttributesDescriptor("Punctuation", BcfgSyntaxHighlighter.PUNCTUATION),
        new AttributesDescriptor("Section", BcfgSyntaxHighlighter.SECTION),
        new AttributesDescriptor("Property", BcfgSyntaxHighlighter.PROPERTY),
        new AttributesDescriptor("Value", BcfgSyntaxHighlighter.VALUE),
        new AttributesDescriptor("Comment", BcfgSyntaxHighlighter.COMMENT),
        new AttributesDescriptor("File", BcfgSyntaxHighlighter.FILE_PATH),
        new AttributesDescriptor("Error", BcfgSyntaxHighlighter.BAD_CHARACTER),
      };

  @Nullable
  @Override
  public Icon getIcon() {
    return BcfgFileType.DEFAULT_ICON;
  }

  @NotNull
  @Override
  public SyntaxHighlighter getHighlighter() {
    return new BcfgSyntaxHighlighter();
  }

  @NotNull
  @Override
  public String getDemoText() {
    return "# You are reading a .buckconfig file.\n"
        + "; The semi-colon can also be used for commenting.\n"
        + "       # And comments can be indented\n"
        + "[foo]\n"
        + "    bar = http://en.wikipedia.org/\n"
        + "    baz = \"quoted param\"\n"
        + "    qux = line \\\n"
        + "          continuations \\\n"
        + "          including \\\n"
        + "          \"quoted strings\" \\\n"
        + "          and multiple keys on one line\\\n"
        + "    \n"
        + "    keys#can.have_some!odd,chars : \"and ':' can be used instead of '='\"\n"
        + "\n"
        + "<file:path/to/required.bcfg>\n"
        + "\n"
        + "<?file:path/to/optional.bcfg>\n"
        + "\n"
        + "[unicode]\n"
        + "    beer = \"Must be quoted: \\U1F37A\"\n"
        + "    tab = \"Also must be quoted: \\u0009\"\n"
        + "[errors]\n"
        + "   12345.key = must start with alpha or _\n"
        + "   unmatched.quote = \"in expression\n";
  }

  @Nullable
  @Override
  public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
    return null;
  }

  @NotNull
  @Override
  public AttributesDescriptor[] getAttributeDescriptors() {
    return DESCRIPTORS;
  }

  @NotNull
  @Override
  public ColorDescriptor[] getColorDescriptors() {
    return ColorDescriptor.EMPTY_ARRAY;
  }

  @NotNull
  @Override
  public String getDisplayName() {
    return "Buckconfig";
  }
}
