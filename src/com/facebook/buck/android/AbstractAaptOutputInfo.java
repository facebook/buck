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

package com.facebook.buck.android;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/**
 * Encapsulates information about a rule that runs "aapt", for consumption by downstream rules.
 *
 * <p>We break this data out into a separate object to allow different implementations of aapt.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAaptOutputInfo {
  public abstract SourcePath getPathToRDotTxt();

  public abstract SourcePath getPrimaryResourcesApkPath();

  public abstract SourcePath getAndroidManifestXml();

  public abstract SourcePath getAaptGeneratedProguardConfigFile();
}
