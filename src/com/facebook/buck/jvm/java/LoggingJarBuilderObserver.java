/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.util.zip.CustomZipEntry;
import com.facebook.buck.util.zip.JarBuilder;
import com.facebook.buck.util.zip.JarEntrySupplier;
import java.util.logging.Level;
import java.util.zip.ZipEntry;

public class LoggingJarBuilderObserver implements JarBuilder.Observer {
  private final JavacEventSink eventSink;

  public LoggingJarBuilderObserver(JavacEventSink eventSink) {
    this.eventSink = eventSink;
  }

  @Override
  public void onDuplicateEntry(String jarFile, JarEntrySupplier entrySupplier) {
    CustomZipEntry entry = entrySupplier.getEntry();
    eventSink.reportEvent(
        determineSeverity(entry),
        "Duplicate found when adding '%s' to '%s' from '%s'",
        entry.getName(),
        jarFile,
        entrySupplier.getEntryOwner());
  }

  @Override
  public void onEntryOmitted(String jarFile, JarEntrySupplier entrySupplier) {
    String entryName = entrySupplier.getEntry().getName();
    entryName = JarBuilder.pathToClassName(entryName);
    eventSink.reportEvent(Level.FINE, "%s is excluded from the Jar", entryName);
  }

  private Level determineSeverity(ZipEntry entry) {
    return entry.isDirectory() ? Level.FINE : Level.INFO;
  }
}
