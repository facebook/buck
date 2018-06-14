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

package buck.exotest.meta;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/**
 * This activity logs information found in the exo test app's AndroidManifest.xml.
 *
 * <p>It will try to load that app's icon, label, and some <meta-data/> fields. It does some
 * validation itself (like comparing the icon against Android's default icon) and then logs
 * information about the values to be checked by the test script.
 */
public class ExoMetaLogActivity extends Activity {
  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    try {
      ApplicationInfo appInfo =
          getPackageManager().getApplicationInfo("buck.exotest", PackageManager.GET_META_DATA);

      Bitmap icon = getIcon(appInfo);
      Bitmap defaultIcon =
          ((BitmapDrawable) getPackageManager().getApplicationIcon(getApplicationInfo()))
              .getBitmap();
      if (icon == null) {
        Log.i("EXOPACKAGE_TEST_META", "Found no icon");
      } else if (icon.sameAs(defaultIcon)) {
        Log.i("EXOPACKAGE_TEST_META", "Found default icon");
      } else {
        Log.i("EXOPACKAGE_TEST_META", "META_ICON=" + icon.getWidth() + "_" + icon.getHeight());
      }
      String name = getName(appInfo);
      if (name == null) {
        Log.i("EXOPACKAGE_TEST_META", "Found no name");
      } else {
        Log.i("EXOPACKAGE_TEST_META", "META_NAME=" + name);
      }
      String[] meta = getMeta(appInfo);
      if (meta == null) {
        Log.i("EXOPACKAGE_TEST_META", "Found no metadata");
      } else {
        String metaStr = "<";
        for (int i = 0; i < meta.length; i++) {
          metaStr += (i == 0 ? "" : ",") + meta[i];
        }
        metaStr += ">";
        Log.i("EXOPACKAGE_TEST_META", "META_DATA=" + metaStr);
      }
    } catch (Exception e) {
      Log.i("EXOPACKAGE_TEST_META_DEBUG", "Got an exception", e);
    }
    Log.i("EXOPACKAGE_TEST_META", "FINISHED");
    finish();
  }

  public String getName(ApplicationInfo appInfo) {
    try {
      return getPackageManager().getApplicationLabel(appInfo).toString();
    } catch (Exception e) {
      Log.i("EXOPACKAGE_TEST_META_DEBUG", "getName threw exception", e);
      return null;
    }
  }

  public Bitmap getIcon(ApplicationInfo appInfo) {
    try {
      return ((BitmapDrawable) getPackageManager().getApplicationIcon(appInfo)).getBitmap();
    } catch (Exception e) {
      Log.i("EXOPACKAGE_TEST_META_DEBUG", "getIcon threw exception", e);
      return null;
    }
  }

  public String[] getMeta(ApplicationInfo appInfo) {
    try {
      return getPackageManager()
          .getResourcesForApplication(appInfo)
          .getStringArray(appInfo.metaData.getInt("app_meta"));
    } catch (Exception e) {
      Log.i("EXOPACKAGE_TEST_META_DEBUG", "getMeta threw exception", e);
      return null;
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    // Workaround for the fact that that "am force-stop" doesn't work on Gingerbread.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
      Process.killProcess(Process.myPid());
    }
  }
}
