import std.stdio;

extern (C++) int quus(int x, int y);

void main()
{
  writefln("1 + 1 = %d", quus(1, 1));
  writefln("100 + 1 = %d", quus(100, 1));
}
