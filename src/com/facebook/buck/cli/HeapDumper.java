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
package com.facebook.buck.cli;

import com.google.common.base.Verify;
import java.lang.management.ManagementFactory;
import java.lang.management.PlatformManagedObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.management.MBeanServer;

/**
 * Lightweight utility for capturing a heap dump programmatically.
 *
 * <p>Note: This is likely not robust enough to use in real code. It's currently only used in
 * diagnostic commands.
 */
public class HeapDumper {
  private static final String HOTSPOT_BEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";
  private static final String HOTSPEAN_BEAN_CLASS_NAME =
      "com.sun.management.HotSpotDiagnosticMXBean";
  private static final PlatformManagedObject HOTSPOT_DIAGNOSTIC_BEAN;
  private static final Method DUMP_METHOD;

  /** Type of heap dump to collect. */
  enum DumpType {
    REACHABLE_OBJECTS_ONLY,
    INCLUDE_NONREACHABLE_OBJECTS,
  }

  static {
    HOTSPOT_DIAGNOSTIC_BEAN = getDiagnosticBean();
    DUMP_METHOD = getDumpMethod();
  }

  private static Method getDumpMethod() {
    try {
      Class<?> clazz = Class.forName(HOTSPEAN_BEAN_CLASS_NAME);
      Method m = clazz.getMethod("dumpHeap", String.class, boolean.class);
      m.setAccessible(true);
      return m;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static PlatformManagedObject getDiagnosticBean() {
    try {
      Class<? extends PlatformManagedObject> clazz =
          Class.forName("com.sun.management.HotSpotDiagnosticMXBean")
              .asSubclass(PlatformManagedObject.class);
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      PlatformManagedObject bean =
          ManagementFactory.newPlatformMXBeanProxy(server, HOTSPOT_BEAN_NAME, clazz);
      return bean;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Perform possibly heavyweight initialization. */
  public static void init() {
    // Don't do anything. Just forces this class to load.
  }

  /** Dumps the heap to the provided path. */
  public static void dumpHeap(String heapDumpPath, DumpType dumpType) {
    try {
      Verify.verify(
          dumpType == DumpType.REACHABLE_OBJECTS_ONLY
              || dumpType == DumpType.INCLUDE_NONREACHABLE_OBJECTS);

      // https://docs.oracle.com/javase/8/docs/jre/api/management/extension/com/sun/management/HotSpotDiagnosticMXBean.html
      DUMP_METHOD.invoke(
          HOTSPOT_DIAGNOSTIC_BEAN, heapDumpPath, dumpType == DumpType.REACHABLE_OBJECTS_ONLY);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }
}
