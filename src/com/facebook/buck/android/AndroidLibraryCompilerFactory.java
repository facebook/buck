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

package com.facebook.buck.android;

import com.facebook.buck.android.AndroidLibraryDescription.JvmLanguage;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.JavacFactory;

/**
 * Factory providing implementations of {@link ConfiguredCompilerFactory} for the specified {@code
 * language}. {@link AndroidLibraryDescription} uses this factory to handle multiple JVM languages.
 * Implementations should provide a compiler implementation for every {@link JvmLanguage}. See
 * {@link DefaultAndroidLibraryCompiler}
 */
public interface AndroidLibraryCompilerFactory {

  ConfiguredCompilerFactory getCompiler(JvmLanguage language, JavacFactory javacFactory);
}
