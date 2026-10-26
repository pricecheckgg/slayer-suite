package com.slayersuite;

import com.slayersuite.trip.PackingMath;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PackingMathTest
{
	@Test
	public void packsWithMarginAndRoundsUp()
	{
		// 100 kills, 0.25 food per kill, 2 prayer points per kill at 31 per dose, 0.1 other doses per kill, 15% margin
		PackingMath.Pack p = PackingMath.pack(100, 0.25, 2.0, 0.1, 31, 0, 6, 0.15);
		assertEquals(29, p.getFood());          // 115 * 0.25 = 28.75
		assertEquals(8, p.getPrayerDoses());    // 230 / 31 = 7.4
		assertEquals(2, p.getPrayerPotions());
		assertEquals(12, p.getOtherDoses());    // 11.5
		assertEquals(3, p.getOtherPotions());
		assertEquals(0, p.getAntifireDoses());
		assertEquals(34, p.getSlots());
		assertEquals(2, p.getTrips());
	}

	@Test
	public void nothingNeededIsOneTrip()
	{
		PackingMath.Pack p = PackingMath.pack(50, 0, 0, 0, 31, 0, 6, 0.15);
		assertEquals(0, p.getSlots());
		assertEquals(1, p.getTrips());
	}

	@Test
	public void antifireByMinutes()
	{
		PackingMath.Pack p = PackingMath.pack(100, 0, 0, 0, 31, 40, 6, 0.0);
		assertEquals(7, p.getAntifireDoses());  // 40 / 6 = 6.7
		assertEquals(2, p.getSlots());          // two potions
	}
}
