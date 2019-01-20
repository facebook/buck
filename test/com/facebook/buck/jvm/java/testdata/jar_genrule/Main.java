import java.io.IOException;
import java.io.InputStream;

public class Main {
  public static void main(String[] args) throws IOException {
    // Copy the contents of resources.txt within the jar to stdout.
    try (InputStream inputStream = Main.class.getResourceAsStream("/resource.txt")) {
      byte[] buffer = new byte[4096];
      while (true) {
        int size = inputStream.read(buffer);
        if (size < 0) {
          break;
        }
        System.out.write(buffer, 0, size);
      }
    }
  }
}
