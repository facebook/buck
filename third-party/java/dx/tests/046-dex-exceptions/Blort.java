/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class Blort
{
    public static int maybeThrow(int x) {
        if (x < 10) {
            throw new RuntimeException();
        }

        return x;
    }

    public static int exTest1(int x) {
        try {
            maybeThrow(x);
            return 1;
        } catch (RuntimeException ex) {
            return 2;
        }
    }

    public static int exTest2(int x) {
        try {
            x++;
            x = maybeThrow(x);
        } catch (RuntimeException ex) {
            return 1;
        }

        // Since the code in the try block can't possibly throw, there
        // should not be a catch in the final result.
        try {
            x++;
        } catch (RuntimeException ex) {
            return 2;
        }

        try {
            return maybeThrow(x);
        } catch (RuntimeException ex) {
            return 3;
        }
    }
}
