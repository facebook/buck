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

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.jvm.java.abi.source.api.FrontendOnlyJavacTaskProxy;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTaskProxyImpl;
import com.sun.source.util.JavacTask;
import javax.tools.JavaCompiler;

public class FrontendOnlyJavacTaskProxyImpl extends BuckJavacTaskProxyImpl
    implements FrontendOnlyJavacTaskProxy {
  private final FrontendOnlyJavacTask javacTask;

  public FrontendOnlyJavacTaskProxyImpl(JavaCompiler.CompilationTask task) {
    this(new FrontendOnlyJavacTask((JavacTask) task));
  }

  public FrontendOnlyJavacTaskProxyImpl(FrontendOnlyJavacTask javacTask) {
    super(javacTask);
    this.javacTask = javacTask;
  }

  @Override
  public FrontendOnlyJavacTask getInner() {
    return javacTask;
  }
}
