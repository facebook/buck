// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

extern "C" {
void foo_left();
void foo_right();
}

int main() {
  foo_left();
  foo_right();
}
