// Should generate a `-Wshadow` warning.
void foo(int x) {
  for (int x = 0; x < 4; x++);
}
