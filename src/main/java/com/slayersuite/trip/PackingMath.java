package com.slayersuite.trip;

import lombok.Value;

/** What to take for the kills left, from your own consumption per kill. Pure, tested. */
public final class PackingMath
{
	/** Inventory slots assumed free for supplies once the task's gear and teleports are in. */
	public static final int FREE_SLOTS = 24;

	private PackingMath()
	{
	}

	@Value
	public static class Pack
	{
		int food;
		int prayerDoses;
		int prayerPotions;
		int otherDoses;
		int otherPotions;
		int antifireDoses;
		int slots;
		int trips;
	}

	/**
	 * @param remaining kills left
	 * @param foodPerKill pieces eaten per kill
	 * @param prayerPerKill prayer points spent per kill
	 * @param dosesPerKill other potion doses per kill
	 * @param pointsPerDose what one restore dose gives at your level
	 * @param antifireMinutes minutes of dragonfire protection the task still needs, 0 when none
	 * @param minutesPerAntifireDose minutes one dose lasts
	 * @param margin spare fraction, e.g. 0.15
	 */
	public static Pack pack(int remaining, double foodPerKill, double prayerPerKill, double dosesPerKill, int pointsPerDose,
		double antifireMinutes, int minutesPerAntifireDose, double margin)
	{
		double k = Math.max(0, remaining) * (1 + Math.max(0, margin));
		int food = (int) Math.ceil(k * Math.max(0, foodPerKill));
		int prayerDoses = pointsPerDose > 0 ? (int) Math.ceil(k * Math.max(0, prayerPerKill) / pointsPerDose) : 0;
		int prayerPotions = (prayerDoses + 3) / 4;
		int otherDoses = (int) Math.ceil(k * Math.max(0, dosesPerKill));
		int otherPotions = (otherDoses + 3) / 4;
		int antifireDoses = antifireMinutes > 0 && minutesPerAntifireDose > 0 ? (int) Math.ceil(antifireMinutes * (1 + margin) / minutesPerAntifireDose) : 0;
		int antifirePotions = (antifireDoses + 3) / 4;
		int slots = food + prayerPotions + otherPotions + antifirePotions;
		int trips = slots == 0 ? 1 : (slots + FREE_SLOTS - 1) / FREE_SLOTS;
		return new Pack(food, prayerDoses, prayerPotions, otherDoses, otherPotions, antifireDoses, slots, trips);
	}
}
