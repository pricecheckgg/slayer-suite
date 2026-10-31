package com.slayersuite;

import com.slayersuite.history.SuperiorMath;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SuperiorMathTest
{
	@Test
	public void tableDenominatorMatchesTheWiki()
	{
		// 1/171.2 at 5 Slayer, 1/90.5 at 62, 1/31.8 at 90, 1/20 at 95
		assertEquals(171.2, SuperiorMath.tableDenominator(5), 0.05);
		assertEquals(90.5, SuperiorMath.tableDenominator(62), 0.05);
		assertEquals(31.8, SuperiorMath.tableDenominator(90), 0.05);
		assertEquals(20.0, SuperiorMath.tableDenominator(95), 0.001);
	}

	@Test
	public void wyrmsPlainAndBoosted()
	{
		// a wyrm superior: heart 1/724 and any unique 1/80 plain; +265% makes them 1/198 and 1/22
		assertEquals("1/724", SuperiorMath.odds(SuperiorMath.heartOrGem(62, 0)));
		assertEquals("1/80", SuperiorMath.odds(SuperiorMath.anyUnique(62, 0)));
		assertEquals("1/198", SuperiorMath.odds(SuperiorMath.heartOrGem(62, 265)));
		assertEquals("1/22", SuperiorMath.odds(SuperiorMath.anyUnique(62, 265)));
		assertEquals(3.65 / SuperiorMath.tableDenominator(62), SuperiorMath.tableChance(62, 265), 1e-9);
	}

	@Test
	public void darkBeastsAtFullBoost()
	{
		// 1/31.8 per table, times four: any unique about 1/7, a heart about 1/64
		assertEquals("1/7.1", SuperiorMath.odds(SuperiorMath.anyUnique(90, 300)));
		assertEquals("1/64", SuperiorMath.odds(SuperiorMath.heartOrGem(90, 300)));
	}

	@Test
	public void chancesNeverPassOneAndOddsHandleNothing()
	{
		assertEquals(1.0, SuperiorMath.tableChance(99, 100_000), 0.0);
		assertEquals(1.0, SuperiorMath.anyUnique(99, 100_000), 0.0);
		assertEquals("--", SuperiorMath.odds(0));
		assertEquals("--", SuperiorMath.odds(-1));
		// a negative boost counts as none
		assertEquals(SuperiorMath.tableChance(70, 0), SuperiorMath.tableChance(70, -40), 0.0);
	}
}
