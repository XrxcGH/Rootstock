package org.pumpkinlib.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.networktables.DoublePublisher;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.NetworkTableListenerPoller;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.core.config.ConfigRegistry;

/**
 * The loop-time budget of live tuning: <b>one poller, one {@code readQueue()} per loop, whatever the
 * tunable count</b>.
 *
 * <p><strong>What this replaces and why the number matters.</strong> The common FRC tunable-number
 * class performs one {@code DoubleSubscriber.get()} per tunable per loop. On a twelve-mechanism
 * robot that is roughly 250 JNI crossings every 20 ms, and it is invisible: nothing reports it,
 * nothing budgets it, and the cost grows with exactly the thing a team does more of as the season
 * goes on. A single {@link NetworkTableListenerPoller} subscribed to the {@code /Tuning/} prefix
 * costs one crossing per loop no matter how many tunables exist, and loses nothing, because the
 * queue holds every change rather than the latest one.
 *
 * <p><strong>How that is measured here, honestly.</strong> A JNI call count is not observable from
 * Java, so the property is established two ways that together pin it:
 *
 * <ol>
 *   <li><b>Structurally</b> — the transport declares exactly one poller field and <em>no</em>
 *       subscriber field anywhere, including in its per-tunable handle classes, so there is no
 *       per-tunable read to scale. Registering 250 more tunables does not create a second poller.
 *   <li><b>Behaviourally</b> — a value written on the wire is invisible to {@code get()} until
 *       {@code drain()} runs, however many times {@code get()} is called in between, and <em>one</em>
 *       drain delivers all 250 changes at once. So the number of queue reads per loop is one, and
 *       the number of wire reads per {@code get()} is zero.
 * </ol>
 *
 * <p>{@code @Tag("hal")}: {@code TuningRegistry}'s static initialiser calls {@code
 * NetworkTableInstance.getDefault()}. Without WPILib's natives that call does not throw — the loader
 * writes to stderr and calls {@code System.exit(1)} — so merely mentioning the class kills a
 * native-free JVM.
 */
@Tag("hal")
final class TuningRegistryBudgetTest {

  private NetworkTableInstance m_instance;

  private final List<DoublePublisher> m_dashboardPublishers = new ArrayList<>();

  @BeforeEach
  void freshRegistry() {
    TuningRegistry.resetForTest();
    ConfigRegistry.clearForTest();
    m_instance = NetworkTableInstance.create();
    m_instance.startLocal();
    TuningRegistry.setNetworkTableInstance(m_instance);
    TuningRegistry.setTuningEnabled(true);
  }

  @AfterEach
  void closeEverything() {
    for (DoublePublisher publisher : m_dashboardPublishers) {
      publisher.close();
    }
    m_dashboardPublishers.clear();
    TuningRegistry.resetForTest();
    ConfigRegistry.clearForTest();
    m_instance.close();
  }

  /** Writes a value the way a dashboard would: a separate publisher on the same instance. */
  private void dashboardWrites(String fullKey, double value) {
    DoublePublisher publisher = m_instance.getDoubleTopic(fullKey).publish();
    m_dashboardPublishers.add(publisher);
    publisher.set(value);
    m_instance.flushLocal();
    // Deterministic instead of a sleep: block until ntcore has delivered every queued listener
    // event, so the poller's queue is guaranteed to hold the write we just made.
    m_instance.waitForListenerQueue(2.0);
  }

  /** The one transport the registry owns, as the concrete type, so its privates can be read. */
  private static AdvantageKitTunableTransport transport() {
    TunableTransport transport = TuningRegistry.transport();
    assertTrue(
        transport instanceof AdvantageKitTunableTransport,
        "the shipped transport is the AdvantageKit one; this test reads its internals");
    return (AdvantageKitTunableTransport) transport;
  }

  private static Object fieldValue(Object owner, String name) {
    try {
      Field field = owner.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(owner);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "AdvantageKitTunableTransport has no field \""
              + name
              + "\" any more. It was renamed, so this budget test no longer measures the thing it "
              + "claims to. Fix: update the field name here.",
          e);
    }
  }

  @Nested
  @DisplayName("structurally, there is nothing that could scale")
  final class Structure {

    @Test
    @DisplayName("the transport declares exactly one poller and no subscriber at all")
    void onePollerNoSubscribers() {
      long pollers = 0;
      List<String> subscribers = new ArrayList<>();

      for (Field field : AdvantageKitTunableTransport.class.getDeclaredFields()) {
        if (field.getType() == NetworkTableListenerPoller.class) {
          pollers++;
        }
        if (field.getType().getSimpleName().contains("Subscriber")) {
          subscribers.add(field.getName() + " : " + field.getType().getSimpleName());
        }
      }

      assertEquals(1, pollers, "one poller for the whole robot, not one per tunable");
      assertTrue(
          subscribers.isEmpty(),
          "a subscriber field is a per-loop read waiting to happen: " + subscribers);

      // And in the per-tunable handle classes, which is where a per-tunable read would actually
      // have to live.
      for (Class<?> nested : AdvantageKitTunableTransport.class.getDeclaredClasses()) {
        for (Field field : nested.getDeclaredFields()) {
          assertFalse(
              field.getType().getSimpleName().contains("Subscriber"),
              nested.getSimpleName()
                  + "."
                  + field.getName()
                  + " is a "
                  + field.getType().getSimpleName()
                  + ", so reads would scale with the tunable count");
        }
      }
    }

    @Test
    @DisplayName("250 tunables share one transport and one poller")
    void thePollerCountDoesNotGrow() {
      TuningRegistry.tunable("Budget", "first", 1.0, "");
      AdvantageKitTunableTransport first = transport();
      Object firstPoller = fieldValue(first, "m_poller");
      assertNotNull(firstPoller);

      for (int i = 0; i < 250; i++) {
        TuningRegistry.tunable("Budget", "k" + i, i, "V");
      }

      assertSame(first, transport(), "the transport is built once and reused");
      assertSame(firstPoller, fieldValue(transport(), "m_poller"), "and so is its poller");
      assertEquals(251, TuningRegistry.tunables().size());
      assertTrue(
          transport().describe().contains("one readQueue() per loop"),
          transport().describe());
    }

    @Test
    @DisplayName("a robot that never tunes anything never builds a transport at all")
    void theTransportIsLazy() {
      TuningRegistry.resetForTest();
      TuningRegistry.setNetworkTableInstance(m_instance);
      assertTrue(
          TuningRegistry.describe().contains("transport not built yet"),
          TuningRegistry.describe());
    }
  }

  @Nested
  @DisplayName("behaviourally, reads do not touch the wire and one drain carries everything")
  final class Behaviour {

    @Test
    @DisplayName("get() is a cached field read: the wire value is invisible until drain() runs")
    void getDoesNotTouchTheWire() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 128.0, "V/m");
      TuningRegistry.drainPoller(); // registers the key set

      dashboardWrites(kp.fullKey(), 999.0);

      for (int i = 0; i < 10_000; i++) {
        assertEquals(
            128.0,
            kp.get(),
            0.0,
            "ten thousand reads must not produce ten thousand wire reads — the value is stale "
                + "on purpose until the one drain per loop happens");
      }

      TuningRegistry.drainPoller();
      assertEquals(999.0, kp.get(), 0.0, "and after the single drain it is live");
    }

    @Test
    @DisplayName("one drain delivers 250 dashboard edits, not 250 drains")
    void oneDrainCarriesEveryChange() {
      List<TunableDouble> tunables = new ArrayList<>();
      for (int i = 0; i < 250; i++) {
        tunables.add(TuningRegistry.tunable("Budget", "k" + i, i, "V"));
      }
      TuningRegistry.drainPoller();

      for (int i = 0; i < 250; i++) {
        dashboardWrites(tunables.get(i).fullKey(), 1000.0 + i);
      }

      // Exactly one drain. If reads scaled with the tunable count this would need 250.
      TuningRegistry.drainPoller();

      for (int i = 0; i < 250; i++) {
        assertEquals(
            1000.0 + i,
            tunables.get(i).get(),
            0.0,
            "tunable " + i + " did not receive its edit from the single queue read");
      }
    }

    @Test
    @DisplayName("intermediate values are not coalesced away: the queue holds every change")
    void everyChangeSurvivesTheQueue() {
      List<Double> seen = new ArrayList<>();
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 0.0, "V/m");
      kp.onChange(seen::add);
      TuningRegistry.drainPoller();

      DoublePublisher publisher = m_instance.getDoubleTopic(kp.fullKey()).publish();
      m_dashboardPublishers.add(publisher);
      for (int i = 1; i <= 5; i++) {
        publisher.set(i);
      }
      m_instance.flushLocal();
      m_instance.waitForListenerQueue(2.0);

      TuningRegistry.drainPoller();

      assertEquals(
          List.of(1.0, 2.0, 3.0, 4.0, 5.0),
          seen,
          "a poller holds every change; a per-tunable get() would have seen only the last one");
      assertEquals(5.0, kp.get(), 0.0);
    }

    @Test
    @DisplayName("a topic outside /Tuning/ is never delivered, so the queue cannot be flooded")
    void onlyTheTuningPrefixIsListenedTo() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 128.0, "V/m");
      TuningRegistry.drainPoller();

      dashboardWrites("/SmartDashboard/somethingElse", 42.0);
      dashboardWrites("/PumpkinTuner/State", 7.0);
      TuningRegistry.drainPoller();

      assertEquals(128.0, kp.get(), 0.0);
      assertEquals(TuningRegistry.kPrefix, AdvantageKitTunableTransport.kPrefix);
      assertEquals("/Tuning/", TuningRegistry.kPrefix);
    }

    @Test
    @DisplayName("the disabled path never touches the poller at all")
    void disabledCostsOneBranch() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 128.0, "V/m");
      TuningRegistry.setTuningEnabled(false);

      dashboardWrites(kp.fullKey(), 999.0);
      TuningRegistry.drainPoller();

      assertEquals(
          128.0,
          kp.get(),
          0.0,
          "with tuning off the compile-time default is returned and the wire is never read");

      // Turning it back on picks the queued edit up on the next drain, because the queue kept it.
      TuningRegistry.setTuningEnabled(true);
      TuningRegistry.drainPoller();
      assertEquals(999.0, kp.get(), 0.0);
    }
  }

  @Nested
  @DisplayName("the schema this budget is built on")
  final class Schema {

    @Test
    @DisplayName("a flag namespace that collides with a mechanism is refused, not accepted")
    void flagNamespaceCollisionIsCollected() {
      TuningRegistry.resetForTest();
      TuningRegistry.setNetworkTableInstance(m_instance);

      org.pumpkinlib.control.FakeTarget target =
          new org.pumpkinlib.control.FakeTarget().named("Elevator");
      assertTrue(TuningRegistry.register(target).isEmpty(), "the mechanism registers cleanly");

      TunableBoolean flag = TuningRegistry.tunableFlag("Elevator", "pitSwitch", true);

      assertFalse(TuningRegistry.errors().isEmpty(), "the collision must be collected");
      assertTrue(
          TuningRegistry.errors().stream().anyMatch(e -> e.describe().contains("seventeen")),
          TuningRegistry.errors().get(0).describe());
      assertTrue(flag.get(), "and the returned handle is pinned to its default, never null");
    }

    @Test
    @DisplayName("the seventeen-double schema is seven gains plus ten control values")
    void seventeenDoubles() {
      assertEquals(
          7,
          org.pumpkinlib.control.GainId.values().length,
          "seven gains, generated from the enum and never typed");
      assertEquals(10, TuningRegistry.kControlTunables.size());
      assertEquals(17, org.pumpkinlib.control.GainId.values().length
          + TuningRegistry.kControlTunables.size());
    }

    @Test
    @DisplayName("every tunable is push-registered with the config registry")
    void tunablesReachTheConfigRegistry() {
      TuningRegistry.tunable("Vision", "maxTagDistance", 6.0, "m");
      assertTrue(
          ConfigRegistry.tunables().stream()
              .anyMatch(t -> t.key().equals("Vision/maxTagDistance")),
          "the snapshot and the drift check see live tuned values through this seam, and core "
              + "never names the tuning package");
    }
  }
}
