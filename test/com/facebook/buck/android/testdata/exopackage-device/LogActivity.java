/*
 * Copyright 2014-present Facebook, Inc.
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

package exotest;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import buck.exotest.R;
import com.facebook.buck.android.support.exopackage.DelegatingClassLoader;
import com.facebook.buck.android.support.exopackage.ExopackageSoLoader;
import java.io.IOException;
import java.util.Scanner;

public class LogActivity extends Activity {

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);

    Log.i("EXOPACKAGE_TEST", "VALUE=" + Value.VALUE);

    ExopackageSoLoader.loadLibrary("one");
    ExopackageSoLoader.loadLibrary("two");

    Log.i("EXOPACKAGE_TEST", "NATIVE_ONE=" + stringOneFromJNI());
    Log.i("EXOPACKAGE_TEST", "NATIVE_TWO=" + stringTwoFromJNI());

    Log.i("EXOPACKAGE_TEST", "RESOURCE=" + getResourceString());
    Log.i("EXOPACKAGE_TEST", "IMAGE=" + getImageString());
    Log.i("EXOPACKAGE_TEST", "ASSET=" + getAssetString());
    Log.i("EXOPACKAGE_TEST", "ASSET_TWO=" + getExtraAssetString());

    Log.i("EXOPACKAGE_TEST", "MODULE_ONE=" + getModularClassValue());

    Log.i("EXOPACKAGE_TEST", "EXITING");

    finish();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    // Workaround for the fact that that "am force-stop" doesn't work on Gingerbread.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
      Process.killProcess(Process.myPid());
    }
  }

  public String getResourceString() {
    String string = getString(R.string.hello);
    return string;
  }

  public String getAssetString(String file) {
    try {
      return new Scanner(getAssets().open(file)).next();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public String getAssetString() {
    return getAssetString("asset.txt");
  }

  public String getExtraAssetString() {
    return getAssetString("asset2.txt");
  }

  public String getImageString() {
    Bitmap bitmap = ((BitmapDrawable) getResources().getDrawable(R.drawable.image)).getBitmap();
    return "png_" + bitmap.getWidth() + "_" + bitmap.getHeight();
  }

  public native String stringOneFromJNI();

  public native String stringTwoFromJNI();

  public String getModularClassValue() {
    try {
      return (String)
          DelegatingClassLoader.getInstance()
              .loadClass("exotest.Module")
              .getDeclaredField("VALUE")
              .get(null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
