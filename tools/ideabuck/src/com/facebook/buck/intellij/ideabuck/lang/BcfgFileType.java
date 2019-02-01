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

package com.facebook.buck.intellij.ideabuck.lang;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

/** File type for {@code .buckconfig} files. */
public class BcfgFileType extends LanguageFileType {

  public static Icon DEFAULT_ICON =
      IconLoader.getIcon("/icons/buckconfig_icon.png", BcfgFileType.class);

  public static final BcfgFileType INSTANCE = new BcfgFileType();

  /** Default file extension for Buck's config files {@code .buckconfig}. */
  public static final String DEFAULT_EXTENSION = "buckconfig";

  private BcfgFileType() {
    super(BcfgLanguage.INSTANCE);
  }

  @Override
  @NotNull
  public String getName() {
    return "Buckconfig";
  }

  @Override
  @NotNull
  public String getDescription() {
    return "Buckconfig file";
  }

  @Override
  @NotNull
  public String getDefaultExtension() {
    return DEFAULT_EXTENSION;
  }

  @Override
  public Icon getIcon() {
    return DEFAULT_ICON;
  }
}
