extern (C++) int quus(int x, int y);

unittest {
  assert(quus(1, 1) == 2);
  assert(quus(57, 18) == 5);
}

void main() {}
