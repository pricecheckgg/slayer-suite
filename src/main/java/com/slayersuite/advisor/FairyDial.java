package com.slayersuite.advisor;

/**
 * The fairy ring's three dials as the game stores them: each is a varbit 0..3, and the letter at
 * each value runs round the dial in the order below. Pure, so the arithmetic can be tested.
 */
public final class FairyDial
{
	/** Letter at each varbit value, dial by dial (the order the dial turns through). */
	public static final String[][] DIALS = {
		{"A", "D", "C", "B"},
		{"I", "L", "K", "J"},
		{"P", "S", "R", "Q"},
	};

	private FairyDial()
	{
	}

	/** True for a three-letter code the dials can show. */
	public static boolean isCode(String code)
	{
		if (code == null || code.length() != 3)
		{
			return false;
		}
		for (int i = 0; i < 3; i++)
		{
			if (index(i, code.charAt(i)) < 0)
			{
				return false;
			}
		}
		return true;
	}

	/** Varbit value showing this letter on the dial, or -1. */
	public static int index(int dial, char letter)
	{
		String want = String.valueOf(Character.toUpperCase(letter));
		for (int i = 0; i < 4; i++)
		{
			if (DIALS[dial][i].equals(want))
			{
				return i;
			}
		}
		return -1;
	}

	/** The code the dials show now. */
	public static String letters(int[] values)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 3; i++)
		{
			int v = values[i];
			sb.append(v >= 0 && v < 4 ? DIALS[i][v] : "?");
		}
		return sb.toString();
	}

	/**
	 * The shorter turn from one value to another: {clicks, direction}, direction +1 for clockwise
	 * and -1 for anticlockwise, clicks 0 when already there. sense is +1 when a clockwise click
	 * raises the varbit, -1 when it lowers it (learned from the first click seen).
	 */
	public static int[] turn(int from, int to, int sense)
	{
		int up = ((to - from) % 4 + 4) % 4;
		if (up == 0)
		{
			return new int[]{0, 1};
		}
		int clockwise = sense >= 0 ? up : 4 - up;
		int anticlockwise = 4 - clockwise;
		return clockwise <= anticlockwise ? new int[]{clockwise, 1} : new int[]{anticlockwise, -1};
	}
}
