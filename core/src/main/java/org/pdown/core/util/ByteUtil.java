package org.pdown.core.util;

public class ByteUtil {

  public static String byteFormat(long size) {
    String[] unit = {"B", "KB", "MB", "GB", "TB", "PB"};
    int pow = 0;
    long temp = size;
    while (temp / 1000D >= 1) {
      pow++;
      temp /= 1000D;
    }
    String fmt = unit[pow];
    return String.format("%.2f", size / Math.pow(1024, pow)) + fmt;
  }
}
