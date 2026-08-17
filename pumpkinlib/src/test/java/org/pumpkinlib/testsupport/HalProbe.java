package org.pumpkinlib.testsupport;

/** Loads the HAL outside any test harness so a native failure prints instead of killing a worker. */
public final class HalProbe {
  private HalProbe() {}

  public static void main(String[] args) {
    System.out.println("java.library.path = " + System.getProperty("java.library.path"));
    try {
      boolean ok = edu.wpi.first.hal.HAL.initialize(500, 0);
      System.out.println("HAL.initialize -> " + ok);
      System.out.println("FPGA time = " + edu.wpi.first.wpilibj.RobotController.getFPGATime());
      edu.wpi.first.hal.HAL.shutdown();
      System.out.println("PROBE OK");
    } catch (Throwable t) {
      System.out.println("PROBE FAILED: " + t);
      t.printStackTrace(System.out);
    }
  }
}
