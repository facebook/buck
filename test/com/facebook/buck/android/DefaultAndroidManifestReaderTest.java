/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class DefaultAndroidManifestReaderTest {

  @Test
  public void testReadPackage() throws IOException {
    AndroidManifestReader manifestReader = DefaultAndroidManifestReader.forString(String.format(
        "<manifest xmlns:android='http://schemas.android.com/apk/res/android' package='%s' />",
        "com.example.package"));
    String packageName = manifestReader.getPackage();
    assertEquals("com.example.package", packageName);
  }

  @Test
  public void testReadVersionCode() throws IOException {
    AndroidManifestReader manifestReader = DefaultAndroidManifestReader.forString(
        "<manifest xmlns:android='http://schemas.android.com/apk/res/android' " +
            "android:versionCode=\"1\" />");
    String versionCode = manifestReader.getVersionCode();
    assertEquals("1", versionCode);
  }

  @Test
  public void testReadInstrumentationTestRunner() throws IOException {
    AndroidManifestReader manifestReader = DefaultAndroidManifestReader.forString(
        "<manifest xmlns:android='http://schemas.android.com/apk/res/android'>" +
        "  <instrumentation android:name='android.test.InstrumentationTestRunner' />" +
        "</manifest>");
    String instrumentationTestRunner = manifestReader.getInstrumentationTestRunner();
    assertEquals("android.test.InstrumentationTestRunner", instrumentationTestRunner);
  }

  @Test
  public void testReadLauncherActivitiesNoneFound() throws IOException {
    AndroidManifestReader manifestReader = DefaultAndroidManifestReader.forString(
        "<manifest xmlns:android='http://schemas.android.com/apk/res/android'>" +
        "  <application>" +
        "    <not-an-activity android:name='com.example.Activity6'>" +
        "      <intent-filter>" +
        "        <action android:name='android.intent.action.MAIN' />" +
        "        <category android:name='android.intent.category.LAUNCHER' />" +
        "      </intent-filter>" +
        "    </not-an-activity>" +
        "  </application>" +
        "</manifest>");
    List<String> found = manifestReader.getLauncherActivities();
    List<String> expected = ImmutableList.of();
    assertEquals(expected, found);
  }

  @Test
  public void testReadLauncherActivities() throws IOException {
    AndroidManifestReader manifestReader = DefaultAndroidManifestReader.forString(
        "<manifest xmlns:android='http://schemas.android.com/apk/res/android'" +
        "          package='com.example'>" +
        "  <application>" +
        "    <activity android:name='com.example.Activity1'>" +
        "      <intent-filter>" +
        "        <action android:name='android.intent.action.MAIN' />" +
        "        <category android:name='android.intent.category.LAUNCHER' />" +
        "      </intent-filter>" +
        "    </activity>" +
        "    <activity android:name='.Activity2'>" +
        "        <activity/> <!-- Make sure a weird manifest doesn't ruin things! -->" +
        "      <intent-filter>" +
        "        <category android:name='android.intent.category.LAUNCHER' />" +
        "        <action android:name='android.intent.action.MAIN' />" +
        "      </intent-filter>" +
        "    </activity>" +
        "    <activity android:name='com.example.Activity3'>" +
        "      <intent-filter>" +
        "        <category android:name='android.intent.category.LAUNCHER' />" +
        "      </intent-filter>" +
        "    </activity>" +
        "    <activity android:name='com.example.Activity4'>" +
        "      <intent-filter>" +
        "        <action android:name='android.intent.action.MAIN' />" +
        "      </intent-filter>" +
        "    </activity>" +
        "  </application>" +
        "</manifest>");
    List<String> found = manifestReader.getLauncherActivities();
    List<String> expected = ImmutableList.of("com.example.Activity1", ".Activity2");
    assertEquals(expected, found);
  }

  @Test
  public void testReadLauncherActivityAliases() throws IOException {
    AndroidManifestReader manifestReader = DefaultAndroidManifestReader.forString(
        "<manifest xmlns:android='http://schemas.android.com/apk/res/android'" +
        "          package='com.example'>" +
        "  <application>" +
        "    <activity-alias android:name='.ActivityAlias1' " +
        "        android:targetActivity='com.example.RealActivity1'>" +
        "      <intent-filter>" +
        "        <category android:name='android.intent.category.LAUNCHER' />" +
        "        <action android:name='android.intent.action.MAIN' />" +
        "      </intent-filter>" +
        "    </activity-alias>" +
        "  </application>" +
        "</manifest>");
    List<String> found = manifestReader.getLauncherActivities();
    List<String> expected = ImmutableList.of(".ActivityAlias1");
    assertEquals(expected, found);
  }
}
