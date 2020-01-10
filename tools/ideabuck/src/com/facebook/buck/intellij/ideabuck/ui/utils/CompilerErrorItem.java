/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.ui.utils;

public class CompilerErrorItem {
  public enum Type {
    ERROR,
    WARNING
  }

  private String mError;
  private String mFilePath;
  private int mColumn;
  private int mLine;
  private Type mType;

  public CompilerErrorItem(String filePath, int line, Type type) {
    mFilePath = filePath;
    mColumn = -1;
    mLine = line;
    mType = type;
    mError = "";
  }

  public void setError(String error) {
    mError = error;
  }

  public void setColumn(int column) {
    mColumn = column;
  }

  public String getError() {
    return mError;
  }

  public String getFilePath() {
    return mFilePath;
  }

  public int getColumn() {
    return mColumn;
  }

  public int getLine() {
    return mLine;
  }

  public Type getType() {
    return mType;
  }
}
