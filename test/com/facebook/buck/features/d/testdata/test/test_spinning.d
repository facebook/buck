public import core.thread;

unittest {
}

void main() {
  for( ; ; ) {
    Thread.sleep( dur!("seconds")( 5 ) );
  }
}