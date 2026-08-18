/**
 * Persistence for tuned values: four tiers, per-value merge, atomic write, and a path back into
 * source.
 *
 * <p>{@link org.pumpkinlib.tuning.persist.TunedValueStore} resolves a mechanism's effective values
 * from the code default, the committed deploy file, the runtime robot file and the live dashboard,
 * in that order, recording a {@link org.pumpkinlib.tuning.persist.ValueSource} per value so "why is
 * my gain not what I typed" is answerable.
 *
 * <p>{@link org.pumpkinlib.tuning.persist.ValueExporter} closes the loop the surveyed FRC libraries
 * leave open: a good dashboard value becomes a committable JSON diff, a paste-ready Java block, and
 * a markdown session record — so a Saturday of tuning survives a redeploy and survives the student
 * who did it graduating.
 */
package org.pumpkinlib.tuning.persist;
