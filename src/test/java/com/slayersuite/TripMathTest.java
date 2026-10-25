package com.slayersuite;

import com.slayersuite.trip.TripMath;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TripMathTest
{
	@Test
	public void perKillAndDry()
	{
		assertEquals(0.5, TripMath.perKill(10, 20), 1e-9);
		assertEquals(0, TripMath.perKill(10, 0), 1e-9);
		assertEquals(40, TripMath.killsUntilDry(20, 0.5));
		assertEquals(-1, TripMath.killsUntilDry(20, 0));
		assertEquals(0, TripMath.killsUntilDry(0, 0.5));
	}

	@Test
	public void emaAndMin()
	{
		assertEquals(0.4, TripMath.ema(0, 0.4, 0.5), 1e-9);
		assertEquals(0.3, TripMath.ema(0.2, 0.4, 0.5), 1e-9);
		assertEquals(0.2, TripMath.ema(0.2, -1, 0.5), 1e-9);
		assertEquals(12, TripMath.minPositive(40, 12, -1));
		assertEquals(-1, TripMath.minPositive(-1, -1));
	}

	@Test
	public void prayerAndAntifirePerDose()
	{
		assertEquals(31, com.slayersuite.trip.TripTracker.prayerPerDose("prayer potion", 99));
		assertEquals(32, com.slayersuite.trip.TripTracker.prayerPerDose("super restore", 99));
		assertEquals(32, com.slayersuite.trip.TripTracker.prayerPerDose("sanfew serum", 99));
		assertEquals(11, com.slayersuite.trip.TripTracker.prayerPerDose("ancient brew", 99));
		assertEquals(6, com.slayersuite.trip.TripTracker.antifireMinutesPerDose("antifire potion"));
		assertEquals(3, com.slayersuite.trip.TripTracker.antifireMinutesPerDose("super antifire potion"));
		assertEquals(12, com.slayersuite.trip.TripTracker.antifireMinutesPerDose("extended antifire"));
		assertEquals(6, com.slayersuite.trip.TripTracker.antifireMinutesPerDose("extended super antifire"));
	}

	@Test
	public void formatK()
	{
		assertEquals("950", TripMath.formatK(950));
		assertEquals("1.5k", TripMath.formatK(1500));
		assertEquals("41k", TripMath.formatK(41_200));
		assertEquals("1.2m", TripMath.formatK(1_230_000));
	}
}
