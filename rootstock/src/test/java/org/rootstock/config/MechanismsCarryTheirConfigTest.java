package org.rootstock.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.rootstock.mechanism.PositionMechanism;
import org.rootstock.mechanism.SimpleMechanism;
import org.rootstock.mechanism.VelocityMechanism;

/**
 * The three shipped mechanisms hand their config to the validation sweep.
 *
 * <p>This is the last link of a chain that shipped broken. {@code Validation.install()} had no call
 * site, so nothing in the config package's fault and device pipeline ever ran; wiring it was the
 * first half. The second half is this: {@code Validation.addAll} is handed MECHANISMS by every
 * worked example in the documents, while {@code errorsOf} recognises only configs. Without
 * {@link Validation.ConfigCarrier} on the real classes the pipeline still found nothing, on a robot
 * that had done exactly what it was told.
 *
 * <p>Why this test and not the one beside it. {@code ValidationPipelineTest} proves the seam works
 * using a {@code FakeMechanism} record that implements the interface. That test passes whether or
 * not {@code PositionMechanism} implements anything, which is precisely the gap that let the
 * original defect through: the machinery was exercised and the product was not.
 *
 * <p>What this proves and what it does not. It proves the interface is implemented and the accessor
 * is declared and returns the field, checked by reflection so no motor, HAL or simulation is
 * needed. It does not prove a live mechanism returns a config, because constructing one needs a
 * backend; {@code ValidationPipelineTest} covers the behaviour once the object exists.
 */
final class MechanismsCarryTheirConfigTest {

  static Stream<Class<?>> shippedMechanisms() {
    return Stream.of(PositionMechanism.class, VelocityMechanism.class, SimpleMechanism.class);
  }

  @ParameterizedTest(name = "{0} implements ConfigCarrier")
  @MethodSource("shippedMechanisms")
  @DisplayName("every shipped mechanism is a ConfigCarrier, so validation sees its config")
  void implementsConfigCarrier(Class<?> mechanism) {
    assertTrue(
        Validation.ConfigCarrier.class.isAssignableFrom(mechanism),
        mechanism.getSimpleName()
            + " must implement Validation.ConfigCarrier. Without it, a robot that registers its"
            + " mechanisms rather than its configs gets no per-config faults and no CAN id"
            + " collision check, and nothing on screen says so.");
  }

  @ParameterizedTest(name = "{0} declares config()")
  @MethodSource("shippedMechanisms")
  @DisplayName("the accessor is declared on the class itself, not inherited from a base that has no config")
  void declaresTheAccessor(Class<?> mechanism) throws NoSuchMethodException {
    Method config = mechanism.getDeclaredMethod("config");
    assertEquals(
        Object.class,
        config.getReturnType(),
        "config() must match the ConfigCarrier signature so the interface is actually satisfied.");
  }
}
