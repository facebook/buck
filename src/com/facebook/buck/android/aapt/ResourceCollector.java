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

package com.facebook.buck.android.aapt;

import com.facebook.buck.android.aapt.RDotTxtEntry.CustomDrawableType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.util.xml.DocumentLocation;
import java.nio.file.Path;
import javax.annotation.Nullable;

interface ResourceCollector {
  void addIntResourceIfNotPresent(
      RType rType, String name, Path path, DocumentLocation documentLocation);

  void addCustomDrawableResourceIfNotPresent(
      RType rType,
      String name,
      Path path,
      DocumentLocation documentLocation,
      CustomDrawableType drawableType);

  void addIntArrayResourceIfNotPresent(
      RType rType, String name, int numValues, Path path, DocumentLocation documentLocation);

  void addResource(
      RType rType,
      RDotTxtEntry.IdType idType,
      String name,
      String idValue,
      @Nullable String parent,
      Path path,
      DocumentLocation documentLocation);
}
