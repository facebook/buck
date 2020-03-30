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

package com.facebook.buck.intellij.ideabuck.test.util;

import com.intellij.mock.MockApplicationEx;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.util.Condition;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.NotNull;

public class MyMockApplication extends MockApplicationEx {
  public MyMockApplication(@NotNull Disposable parentDisposable) {
    super(parentDisposable);
  }

  @Override
  public void invokeLater(@NotNull Runnable runnable, @NotNull Condition expired) {
    invokeLater(runnable);
  }

  public void invokeLater(
      @NotNull Runnable runnable, @NotNull ModalityState state, @NotNull Condition expired) {
    invokeLater(runnable);
  }

  public void invokeLater(@NotNull Runnable runnable) {
    SwingUtilities.invokeLater(runnable);
  }

  public void invokeLater(@NotNull Runnable runnable, @NotNull ModalityState state) {
    invokeLater(runnable);
  }
}
