# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import random


def weighted_choice(weight_dict):
    total = sum(weight_dict.values())
    if total == 0:
        return None
    selected = random.randint(0, total - 1)
    for key, weight in weight_dict.items():
        if selected < weight:
            return key
        selected -= weight
    assert False
