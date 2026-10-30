package com.slayersuite;

import com.slayersuite.history.FloorLootMath;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FloorLootTest
{
	@Test
	public void countdownRoundsUpToTheSecond()
	{
		assertEquals("gone", FloorLootMath.countdown(0));
		assertEquals("gone", FloorLootMath.countdown(-5));
		assertEquals("2:05", FloorLootMath.countdown(125_000));
		assertEquals("1:00", FloorLootMath.countdown(59_999));
		assertEquals("0:05", FloorLootMath.countdown(4_001));
	}

	@Test
	public void vanishesBeforeTheMatchingWindowCloses()
	{
		// the pickup windows run five seconds past the real despawn, so the mark never outlives the item
		assertEquals(120_000L, FloorLootMath.vanishAfter(125_000L));
		assertEquals(180_000L, FloorLootMath.vanishAfter(185_000L));
		assertTrue(FloorLootMath.vanishAfter(3_000L) > 0);
	}
}
