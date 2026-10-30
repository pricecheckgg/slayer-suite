package com.slayersuite;

import com.slayersuite.advisor.FairyDial;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FairyDialTest
{
	@Test
	public void lettersFollowTheGameOrder()
	{
		assertEquals("AIP", FairyDial.letters(new int[]{0, 0, 0}));
		assertEquals("DLS", FairyDial.letters(new int[]{1, 1, 1}));
		assertEquals("CKR", FairyDial.letters(new int[]{2, 2, 2}));
		assertEquals("BJQ", FairyDial.letters(new int[]{3, 3, 3}));
		assertEquals(3, FairyDial.index(0, 'B'));
		assertEquals(2, FairyDial.index(1, 'k'));
		assertEquals(-1, FairyDial.index(2, 'A'));
		assertTrue(FairyDial.isCode("CKS"));
		assertTrue(FairyDial.isCode("bkp"));
		assertFalse(FairyDial.isCode("CKX"));
		assertFalse(FairyDial.isCode("CK"));
		assertFalse(FairyDial.isCode(null));
	}

	@Test
	public void theShorterTurnIsChosen()
	{
		// A (0) to D (1): one clockwise click when clockwise raises the value
		assertArrayEquals(new int[]{1, 1}, FairyDial.turn(0, 1, 1));
		// A (0) to B (3): one anticlockwise click rather than three clockwise
		assertArrayEquals(new int[]{1, -1}, FairyDial.turn(0, 3, 1));
		// two either way: clockwise
		assertArrayEquals(new int[]{2, 1}, FairyDial.turn(0, 2, 1));
		assertArrayEquals(new int[]{0, 1}, FairyDial.turn(2, 2, 1));
		// a dial whose clockwise click lowers the value turns the other way
		assertArrayEquals(new int[]{1, -1}, FairyDial.turn(0, 1, -1));
		assertArrayEquals(new int[]{1, 1}, FairyDial.turn(0, 3, -1));
	}
}
