/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.manifmerger;


/**
 * Unit tests for {@link ManifestMerger}.
 */
public class ManifestMergerTest extends ManifestMergerTestCase {

    /*
     * Wait, I hear you, where are the tests?
     *
     * processTestFiles() uses loadTestData(), which infers the data filename
     * from the caller method name.
     * E.g. the method "test00_noop" will use the data file named "data/00_noop.xml".
     *
     * We could simplify this even further by simply iterating on the data
     * files and getting rid of the test methods; however there's some value in
     * having tests break on a method name that easily points to the data file.
     */

  public void test00_noop() throws Exception {
    processTestFiles();
  }

  public void test01_ignore_app_attr() throws Exception {
    processTestFiles();
  }

  public void test02_ignore_instrumentation() throws Exception {
    processTestFiles();
  }

  public void test10_activity_merge() throws Exception {
    processTestFiles();
  }

  public void test11_activity_dup() throws Exception {
    processTestFiles();
  }

  public void test12_alias_dup() throws Exception {
    processTestFiles();
  }

  public void test13_service_dup() throws Exception {
    processTestFiles();
  }

  public void test14_receiver_dup() throws Exception {
    processTestFiles();
  }

  public void test15_provider_dup() throws Exception {
    processTestFiles();
  }

  public void test16_fqcn_merge() throws Exception {
    processTestFiles();
  }

  public void test17_fqcn_conflict() throws Exception {
    processTestFiles();
  }

  public void test20_uses_lib_merge() throws Exception {
    processTestFiles();
  }

  public void test21_uses_lib_errors() throws Exception {
    processTestFiles();
  }

  public void test25_permission_merge() throws Exception {
    processTestFiles();
  }

  public void test26_permission_dup() throws Exception {
    processTestFiles();
  }

  public void test28_uses_perm_merge() throws Exception {
    processTestFiles();
  }

  public void test30_uses_sdk_ok() throws Exception {
    processTestFiles();
  }

  public void test32_uses_sdk_minsdk_ok() throws Exception {
    processTestFiles();
  }

  public void test33_uses_sdk_minsdk_conflict() throws Exception {
    processTestFiles();
  }

  public void test36_uses_sdk_targetsdk_warning() throws Exception {
    processTestFiles();
  }

  public void test40_uses_feat_merge() throws Exception {
    processTestFiles();
  }

  public void test41_uses_feat_errors() throws Exception {
    processTestFiles();
  }

  public void test45_uses_feat_gles_once() throws Exception {
    processTestFiles();
  }

  public void test47_uses_feat_gles_conflict() throws Exception {
    processTestFiles();
  }

  public void test50_uses_conf_warning() throws Exception {
    processTestFiles();
  }

  public void test52_support_screens_warning() throws Exception {
    processTestFiles();
  }

  public void test54_compat_screens_warning() throws Exception {
    processTestFiles();
  }

  public void test56_support_gltext_warning() throws Exception {
    processTestFiles();
  }
}
