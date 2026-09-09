package org.rootstock.tuning.wizard;

import java.util.LinkedHashMap;
import java.util.Map;
import org.rootstock.control.GainId;

/**
 * The teaching text, shipped as data.
 *
 * <p>This is not a placeholder and it is not generated. Every string below is the actual prose a
 * student reads on the dashboard, written for a fourteen-year-old who has never seen a control
 * loop. It lives here, in the source, because the docs site renders these constants directly — so
 * the robot and the documentation cannot disagree, which is the single most common way a library
 * loses a reader's trust.
 *
 * <p><b>The writing rules the content follows.</b> No equations in the body. No Greek letters. No
 * jargon that an earlier lesson has not defined. Every gain gets a concrete FRC-scale example
 * number. And every lesson names the <em>symptom</em> the student will actually see when the gain is
 * wrong — because "kP too high" is a diagnosis nobody can make from the driver station, and "it
 * overshoots and bounces" is one anybody can.
 *
 * <p><b>Every numeric claim in this file is true of the code that runs.</b> "Cutting the range in
 * half ten times" is {@code HoldBisectionStep.kIterations}. "About one part in six hundred" is the
 * bracket identity {@code 1.6 / 2^10 = 1/640}. "A thirtieth of its travel" is the 3%-of-travel
 * in-window guard. "Above about 0.7 you will not see it bounce" is the threshold {@code PredictStep}
 * uses for its computed answer key. A claim in prose that a constant can silently invalidate is a
 * lie with a delay fuse, so the constants are named in the javadoc of each string.
 */
public final class Lessons {

  /**
   * kS — the stiction tax.
   *
   * <p>Referenced by {@code BreakawayRampStep}, which ramps at {@code
   * BreakawayRampStep.kRampVoltsPerSecond} in both directions and averages, exactly as the last
   * paragraph promises.
   */
  public static final String KS =
      """
      kS - the stiction tax

      Every mechanism has friction. Before anything moves at all, the motor has to push hard \
      enough to break it loose - like sliding a heavy box across a floor: you push, nothing \
      happens, you push harder, nothing happens, and then suddenly it goes.

      kS is how many volts it takes to get to "suddenly." It is measured in volts, and the \
      controller adds it in whichever direction you are trying to move.

      On a typical FRC mechanism kS is somewhere between 0.1 V and 0.8 V. A big number means a lot \
      of friction - worth checking your belt tension before you accept it.

      If kS is too small: small moves never start. The mechanism sits there humming until the \
      error gets big enough.

      If kS is too big: the mechanism twitches and buzzes when it should be sitting perfectly \
      still, because it is being pushed one way, then the other, forever.

      In this step, Rootstock will slowly increase the voltage from zero until it sees the \
      mechanism move, in both directions, and average the two answers.""";

  /** kV — the price of speed. Referenced by the quasistatic half of {@code SysIdSweepStep}. */
  public static final String KV =
      """
      kV - the price of speed

      A spinning motor pushes back. The faster it spins, the more it fights you - that is the same \
      effect that makes a motor work as a generator. So to hold a steady speed, you have to keep \
      paying voltage the whole time.

      kV is the price. It is measured in volts per unit of speed: volts per metre-per-second for \
      something that slides, volts per radian-per-second for something that turns. Want to go \
      twice as fast? Pay twice as much.

      kV is the single most important number in this whole process. Get kV right and your \
      mechanism almost controls itself - the controller can predict, before it even starts moving, \
      exactly how much voltage this move is going to need.

      If kV is too small: the mechanism always runs slower than you asked, and the feedback term \
      has to keep making up the difference.

      If kV is too big: it overshoots your commanded speed and the feedback has to fight it back \
      down.

      In this step, Rootstock will ramp the voltage up very slowly and watch how fast the \
      mechanism goes at each voltage. Slowly, on purpose: if it ramped quickly, some of the \
      voltage would be going into speeding up rather than into holding speed, and we would not be \
      able to tell the two apart.""";

  /** kA — the price of getting moving. Referenced by the dynamic half of {@code SysIdSweepStep}. */
  public static final String KA =
      """
      kA - the price of getting moving

      Things with mass do not change speed instantly. Pushing an empty shopping trolley up to \
      walking pace is easy; pushing a full one up to the same pace takes more effort, even though \
      once they are both rolling they need about the same push to keep going.

      kA is that extra effort: how many extra volts it takes to change speed by one unit every \
      second. A heavy elevator carriage has a big kA. A little turret has a tiny one.

      kA is the hardest number to measure well, because it is only visible during the brief moment \
      when the mechanism is actually speeding up or slowing down. Once it is cruising, kA \
      contributes nothing. That is why this step uses a sudden step of voltage rather than a slow \
      ramp - we need to catch the mechanism in the act of accelerating.

      If kA is wrong: the mechanism lags at the start of every move and overshoots at the end of \
      it, in a way that gets worse the faster you ask it to go.

      Do not be alarmed by this step. It will look and sound more violent than the others. \
      Rootstock has worked out a step size that uses less than half your remaining travel and \
      will stop it after a second and a half.""";

  /**
   * kG — the holding tax.
   *
   * <p>Three numeric claims here are pinned to code. "Ten" is {@code
   * HoldBisectionStep.kIterations}. "About one part in six hundred" is the bracket width identity
   * {@code 1.6 / 2^10 = 1/640}, where 1.6 is the span of the {@code [0.2x, 1.8x]} physics bracket.
   * "A thirtieth of its travel" is {@code HoldBisectionStep.kGuardFractionOfTravel} = 0.03. The last
   * paragraph describes the {@code SIGN_BREAKAWAY} fallback phase.
   */
  public static final String KG =
      """
      kG - the holding tax

      Gravity never turns off. Stop pushing on an elevator carriage and it falls. Stop pushing on \
      an arm and it swings down.

      kG is exactly how many volts it takes to hold still against gravity and do nothing else. It \
      is measured in volts.

      On an elevator, kG is the same everywhere: the carriage weighs the same at the bottom and at \
      the top. On an arm it depends on the angle - biggest when the arm sticks straight out \
      sideways, dropping to zero when it points straight up or straight down. That is why your \
      arm's zero angle has to be horizontal: get it wrong and every angle is wrong.

      If kG is too small: the mechanism settles a little low, every time, and the controller sits \
      there holding a steady voltage trying to lift it.

      If kG is too big: it settles a little high, or creeps upward when you leave it alone.

      If kG is right but the arm's zero is wrong: it droops on one side of its travel and creeps \
      up on the other. That asymmetry is the fingerprint.

      WPILib's own arm guide says you have to get kG right to about four decimal places, which is \
      why Rootstock does not ask you to guess and redeploy. This step first lets go for half a \
      second to see which way your mechanism falls, then holds it and narrows in on the exact \
      holding voltage by cutting the range in half ten times - landing within about one part in \
      six hundred of the range your mechanism's own mass and gearing predict, in five seconds. It \
      never lets it drift more than a thirtieth of its travel while it does this.

      If your mechanism is in brake mode, or its gearbox is stiff enough that it does not move \
      when released, Rootstock finds which way gravity pulls another way: it measures how hard \
      the mechanism is to break loose in each direction. The harder direction is the one gravity \
      is fighting.""";

  /** P — the software spring. Referenced by {@code LqrSuggestStep} when it produces kP. */
  public static final String P =
      """
      P - the software spring

      Everything up to now has been the controller predicting what voltage a move needs. P is the \
      first term that reacts to what actually happened.

      P is a spring made of software. The further you are from where you want to be, the harder it \
      pulls you back. Twice the error, twice the push. That is all it does.

      Its unit is volts per unit of error - volts per metre for an elevator, volts per radian for \
      an arm. That is also why a kP that is right for an elevator looks nothing like a kP that is \
      right for a turret, and why a kP copied off the internet is almost never right for your \
      robot.

      If kP is too small: the mechanism is sluggish. It gets there eventually, or stops slightly \
      short and stays there.

      If kP is too big: it overshoots and bounces, exactly like a spring that is too stiff. Turn \
      it up further and the bouncing never stops. Turn it up further still and the bouncing gets \
      bigger each time, and that is how mechanisms break.

      Rootstock does not ask you to guess kP. It already measured kV and kA, which together \
      describe how your mechanism responds to voltage - so it can calculate a starting kP from two \
      questions that actually mean something: how much error can you live with, and how many volts \
      are you willing to spend fixing it. Tighten the error, or allow more volts, and kP goes up - \
      every time, in that direction. How far up depends on the kV and kA it already measured, so \
      read the number the panel gives you rather than predicting it from a ratio.""";

  /**
   * What the two sliders do. Shown directly under them in the {@code LqrSuggestStep} panel.
   *
   * <p>Two numeric claims are pinned. "About 1.7 times faster" is the square root of three, 1.732,
   * which follows from {@code naturalFrequency = sqrt(kP / kA)}. "Above about 0.7" is the same
   * threshold {@code PredictStep} uses to compute its answer key and the same one the response
   * classifier uses to call a response bouncy.
   *
   * <p>The text deliberately does <b>not</b> claim that kP is the allowed volts divided by the
   * allowed error. An earlier revision said exactly that, here and in {@link #P}, and it is false
   * for the shipped designer: {@code LqrSuggestStep} solves a discrete-time LQR rather than
   * dividing, and on the reference elevator (kV 5.0, kA 0.060, 4 V, 5 mm) that rule predicts 800
   * where the solver returns 55.9 — and halving the accepted error raises kP by 0.2%, not by 100%.
   * What survives is the claim that is true and checkable, and that {@code LessonsNumericClaimTest}
   * pins: kP is monotone non-decreasing in both sliders.
   */
  public static final String WHAT_THE_SLIDERS_DO =
      """
      The two sliders - what you are actually choosing

      Rootstock is not guessing your kP. It already measured how your mechanism responds to \
      voltage, so there is a real answer - but the answer depends on what you want, and these two \
      sliders are how you say it.

      "How much error can I live with" is how fussy you are. Tell it a millimetre and it will \
      fight hard for that millimetre. Tell it a centimetre and it will relax.

      "How many volts may I spend" is how much muscle it is allowed to use getting there. More \
      volts, more push.

      Tighten the error or raise the volts, and kP goes up. Both knobs push the same way, and that \
      is not a coincidence: caring more and being allowed to push harder are the same instruction \
      to a controller. How far it goes up comes out of the kV and kA it just measured, not out of \
      a ratio you can do in your head.

      Now watch the two numbers above the gains, because they are what kP actually means.

      The bounce rate is how fast this mechanism would wobble if you knocked it off target. It \
      goes up with the square root of kP - so tripling kP does not make your elevator three times \
      faster, it makes it about 1.7 times faster.

      The damping is how quickly that wobble dies away. Above about 0.7 you will not see it bounce \
      at all. Below 0.7 you will. Here is the part that catches everyone: raising kP makes the \
      bounce faster and the damping worse, at the same time, from the same slider. That is why \
      turning kP up forever does not work, and why the next number you tune is kD.

      Your mechanism's own kV counts as damping too, for free, before kD does anything. That is \
      why a shooter wheel usually needs no kD at all - and why some elevators do not either. If \
      the damping number above is already comfortably over 0.7 with kD near zero, that is your kV \
      doing the work.""";

  /** D — the shock absorber. Referenced by {@code LqrSuggestStep} when it produces kD. */
  public static final String D =
      """
      D - the shock absorber

      D is the damper that goes with P's spring. Think of the arm on a door that stops it \
      slamming.

      D does not care where you are. It cares how fast the error is shrinking. If you are rushing \
      at the target too fast, D pushes back and slows you down so that you arrive instead of \
      crashing through. That is why P and D go together: P gets you there, D stops the bounce.

      If kD is too small: you get the overshoot-and-ring behaviour from the P lesson.

      If kD is too big: the mechanism gets jittery and buzzy, often at a frequency far too fast \
      for the mechanism to actually be moving that quickly. That is D amplifying the noise in your \
      encoder reading and feeding it straight back into the motor.

      If Rootstock tells you it is halving kD because it saw a 14 Hz buzz, that is what \
      happened.""";

  /**
   * I — the grudge, and why no recipe ever produces it.
   *
   * <p>Shown in the review screen, never as a step. The "residual volts" number the last paragraph
   * points at is the same quantity the refinement rules use to decide between raising kS and
   * raising kG.
   */
  public static final String I =
      """
      I - the grudge, and why you almost certainly do not want it

      I keeps a running total of every bit of error you have ever had, and pushes harder the \
      longer you have been wrong. It sounds like exactly what you want when your mechanism stops \
      just short of its target. It is almost always the wrong tool in FRC, and WPILib says so \
      directly: integral gain is generally not recommended for FRC use.

      Here is why. If your mechanism stops short and stays there, something is pushing against it \
      that nothing in your feedforward is pushing back on. Ninety-nine times out of a hundred that \
      something is friction (fix: raise kS) or gravity (fix: raise kG). Adding I does not remove \
      the force - it just piles up error until it produces enough voltage to cancel it, every \
      single time, from scratch.

      And it has a nasty failure mode called windup. While your mechanism is blocked - jammed, or \
      at a hard stop, or waiting for something else to move out of the way - the total keeps \
      growing. When it is finally free, all of that stored-up push comes out at once. That is how \
      arms slam.

      Rootstock will let you use I. It just will not let you use it carelessly: you have to \
      supply an I-zone (the error band outside which the integrator is switched off) and a voltage \
      cap, in the same breath as kI.

      Before you reach for I, look at the number Rootstock shows you called "residual volts." \
      That is exactly how much voltage the feedback term is holding, right now, to keep the \
      mechanism where it is. That voltage is the feedforward term you are missing. Add it to kS or \
      kG instead.""";

  /** Motion profiles — a plan instead of a wish. Shown at the profile step. */
  public static final String MOTION_PROFILES =
      """
      Motion profiles - a plan instead of a wish

      A setpoint is a wish. If your elevator is at the bottom and you tell it "be at 1.4 metres," \
      you have asked it to teleport. The error is instantly 1.4 metres, so the P term instantly \
      asks for a hundred volts you do not have, the motor saturates, and every gain you tuned \
      stops mattering because the controller is just holding the throttle wide open.

      A motion profile is a plan. Instead of one impossible target, it hands the controller a new, \
      reachable target every twenty milliseconds: a smooth path from where you are to where you \
      want to be, with a speed limit and an acceleration limit that your mechanism can actually \
      meet.

      Two things change once you have one. First, the error is never large, so P and D never \
      saturate. Second - and this is the important one - the profile knows what speed you should \
      be going at this exact instant, which is precisely what kV and kA need in order to predict \
      the voltage. The feedforward can now do almost all of the work, and P and D are left \
      cleaning up a small difference.

      That is the whole reason we tune feedforward first. With a good profile and good kS, kV, kA \
      and kG, your P gain has very little left to do - and a P gain with very little to do is a P \
      gain that cannot shake your robot apart.

      You do not have to guess the speed limit. Rootstock works out the fastest speed your \
      voltage can actually sustain, straight from the kV it just measured, so the limit is, by \
      construction, one your mechanism can do. The acceleration limit is usually not a voltage \
      question at all: on a well-geared elevator the motor can accelerate far harder than the \
      rigging should be asked to. Rootstock will tell you what the motor could do, and how many \
      volts your chosen number actually costs, and then let you choose.""";

  /** Why the order of operations is what it is. Shown once, at the start of every recipe. */
  public static final String WHY_FEEDFORWARD_FIRST =
      """
      Why we do it in this order

      Feedforward is the controller's prediction: given where you want to go and how fast, here is \
      the voltage that should do it. Feedback is the controller's correction: here is a bit extra, \
      because reality did not match the prediction.

      If you tune feedback first, you are asking the correction to do the prediction's job. It can \
      - a big enough kP will drag almost anything to almost anywhere - but it does it by being \
      wrong first and then reacting, which is exactly what overshoot and ringing are. And the \
      bigger you make kP to compensate, the closer you get to the point where the mechanism shakes \
      itself apart.

      So: kS, then kV, then kA, then kG, then kP, then kD. Prediction first, correction second. \
      Every time.

      (One wrinkle for elevators and arms: we measure gravity before we measure friction. You \
      cannot see friction break loose while the mechanism is falling. So Rootstock does a quick \
      gravity pass first, uses it to cancel gravity during the friction and speed measurements, \
      and then re-solves gravity properly at the end using all the data at once. The order you \
      learn the numbers in is still the order above.)""";

  /** Why one team's kP is not another team's kP. Shown at the review screen and in the report. */
  public static final String WHY_UNITS_MATTER =
      """
      Why your kP is not the same as somebody else's kP

      This trips up everyone, so it is worth thirty seconds.

      The number "kP = 50" means nothing on its own. It means volts per something, and every \
      system measures that something differently.

      Rootstock and WPILib measure error in metres or radians, and output in volts. A Kraken \
      running its own loop measures error in motor-shaft rotations, and its output might be volts, \
      or a duty cycle, or amps, depending on which kind of request you send it. A SPARK MAX \
      measures error in rotations and outputs a duty cycle from -1 to 1.

      That is why the published starting kP for the same swerve steer motor is 0.01 on a SPARK MAX \
      and 50 on a TalonFX. Same mechanism, same behaviour, numbers five thousand times apart.

      Rootstock fixes this by having exactly one unit system - volts per SI unit - and converting \
      once, at the boundary, inside the code that talks to your motor controller. Every number you \
      see, save, and paste into your config file is in those units. The number you learn from the \
      WPILib arm tutorial transfers unchanged to your Kraken and to your NEO.

      You can see the exact conversion Rootstock is doing for your mechanism in the "Conversion" \
      line on the dashboard, and in the log at boot. It shows you the one number the whole \
      conversion hangs on: how far your mechanism moves in one rotation.""";

  /** Practice mode — learn this without a robot. Shown in the idle screen and in the report. */
  public static final String PRACTICE_MODE =
      """
      Learn this without a robot

      Everything in this wizard runs in simulation. Start the simulator, pick a mechanism, and \
      tune it exactly the same way, with the same plots and the same steps - except that a \
      simulated elevator does not have a real ceiling to hit and a simulated arm does not have \
      real fingers near it.

      Two different kinds of number come out of this process, and it matters which is which.

      Feedforward gains are checkable answers. kS, kV, kA and kG are properties of the mechanism. \
      There is one right answer and your fit should land on it.

      Feedback gains are choices, not answers. Your kP depends on the two sliders you moved, so \
      two people tuning the same elevator can both be right and get different numbers. Match the \
      response shape, not the number: a clean arrival with a small overshoot and no ringing is \
      right, whether kP came out at 2 or at 20.

      Tune them until you can predict what the plot is going to do before it does it. Then turn \
      the sensor noise on and do it again - a real encoder is never as clean as a simulated one.

      The wizard keeps score. Before each of the important steps it asks you what you think is \
      going to happen, in plain language, three options, no trick answers. It is not a test and \
      getting it wrong blocks nothing - but at the end it tells you how many you called correctly, \
      and so does the report you attach to your pull request. That number is the actual point of \
      this whole practice mode. Aim for all of them on the same mechanism twice in a row before \
      you touch the real robot.""";

  /** The maximum length a lesson may be, in characters. A budget, not a physical law. */
  public static final int kMaxLength = 1800;

  /**
   * Jargon a lesson may never contain.
   *
   * <p>Not because the concepts are unimportant, but because a fourteen-year-old who meets the word
   * "eigenvalue" in paragraph two stops reading paragraph three, and the lesson has then taught
   * nothing at all. Anything on this list has a plain-language substitute somewhere above.
   */
  public static final java.util.List<String> kForbiddenJargon =
      java.util.List.of(
          "Laplace", "eigen", "transfer function", "s-domain", "Nyquist", "root locus");

  private Lessons() {}

  /**
   * The lesson that belongs with a gain.
   *
   * <p>Used by every step that produces a gain, so the mapping from "this step measures kV" to
   * "therefore show the kV lesson" exists once instead of six times.
   *
   * @param id the gain
   * @return the lesson text
   */
  public static String forGain(GainId id) {
    switch (id) {
      case KS:
        return KS;
      case KV:
        return KV;
      case KA:
        return KA;
      case KG:
        return KG;
      case KP:
        return P;
      case KI:
        return I;
      case KD:
        return D;
      default:
        return WHY_FEEDFORWARD_FIRST;
    }
  }

  /**
   * Every lesson, keyed by the name a test or a docs generator would use.
   *
   * <p>This is what makes {@code LessonsTest} a loop over a map instead of eleven copy-pasted
   * assertions that quietly stop covering the twelfth lesson somebody adds.
   *
   * @return an ordered, unmodifiable map from constant name to text
   */
  public static Map<String, String> all() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("KS", KS);
    map.put("KV", KV);
    map.put("KA", KA);
    map.put("KG", KG);
    map.put("P", P);
    map.put("WHAT_THE_SLIDERS_DO", WHAT_THE_SLIDERS_DO);
    map.put("D", D);
    map.put("I", I);
    map.put("MOTION_PROFILES", MOTION_PROFILES);
    map.put("WHY_FEEDFORWARD_FIRST", WHY_FEEDFORWARD_FIRST);
    map.put("WHY_UNITS_MATTER", WHY_UNITS_MATTER);
    map.put("PRACTICE_MODE", PRACTICE_MODE);
    return java.util.Collections.unmodifiableMap(map);
  }
}
