/**
 * The tuning UI surface: every {@code /PumpkinTuner/} NetworkTables topic, and the generated Elastic
 * layout that binds to them.
 *
 * <p>Elastic is the primary surface because it ships with WPILib, it is already open on the
 * driver-station laptop, and it survives 2027. AdvantageScope is the analysis companion and gets the
 * whole {@code /Tuning} table and every plot topic for free, because everything on the wire is plain
 * NT4.
 *
 * <p>Nothing editable lives under {@code /PumpkinTuner/}: editable values are at
 * {@code /Tuning/<Mechanism>/<key>} and only there.
 */
package org.pumpkinlib.tuning.ui;
