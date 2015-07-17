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
    public static int test(int x) {
        if (x == 0) { // line 6
            return 1; // line 7
        } else {
            try {
                x = test(x - 1); // line 10
            } catch (RuntimeException ex) { // line 11
                return 2; // line 12
            }
            x += test(x - 2); // line 14
            return x; // line 15
        }
    }
}
