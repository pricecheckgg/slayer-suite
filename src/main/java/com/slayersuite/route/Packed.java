package com.slayersuite.route;

import net.runelite.api.coords.WorldPoint;

/** A world tile packed into one int: 15 bits of x, 15 bits of y, 2 bits of plane, the layout the routing data uses. */
public final class Packed
{
	private Packed()
	{
	}

	public static int of(int x, int y, int plane)
	{
		return (x & 0x7FFF) | ((y & 0x7FFF) << 15) | ((plane & 3) << 30);
	}

	public static int of(WorldPoint wp)
	{
		return of(wp.getX(), wp.getY(), wp.getPlane());
	}

	public static int x(int p)
	{
		return p & 0x7FFF;
	}

	public static int y(int p)
	{
		return (p >>> 15) & 0x7FFF;
	}

	public static int plane(int p)
	{
		return (p >>> 30) & 3;
	}

	public static WorldPoint toWorld(int p)
	{
		return new WorldPoint(x(p), y(p), plane(p));
	}

	/** Tiles between two points on the ground, the way the game counts a diagonal as one step. */
	public static int chebyshev(int a, int b)
	{
		return Math.max(Math.abs(x(a) - x(b)), Math.abs(y(a) - y(b)));
	}
}
