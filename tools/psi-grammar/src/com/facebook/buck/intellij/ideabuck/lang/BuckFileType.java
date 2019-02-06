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

/** Buck language type */
public class BuckFileType extends LanguageFileType {

  public static Icon DEFAULT_ICON = IconLoader.getIcon("/icons/buck_icon.png", BuckFileType.class);

  public static final BuckFileType INSTANCE = new BuckFileType();

  /** Default file name in the absence of a config setting for {@code buildfile.name}. */
  public static final String DEFAULT_FILENAME = "BUCK";

  /** Default file extension for extensions used by {@code load()} statements. */
  public static final String DEFAULT_EXTENSION = "bzl";

  private BuckFileType() {
    super(BuckLanguage.INSTANCE);
  }

  @Override
  @NotNull
  public String getName() {
    return "Buck";
  }

  @Override
  @NotNull
  public String getDescription() {
    return "Buck file";
  }

  @Override
  @NotNull
  public String getDefaultExtension() {
    return DEFAULT_FILENAME;
  }

  @Override
  public Icon getIcon() {
    return DEFAULT_ICON;
  }
}
