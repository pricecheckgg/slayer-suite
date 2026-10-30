package com.slayersuite.advisor;

import java.awt.Color;

/** Colours for the route chevrons, the markers on the way, the minimap dots and the world map line. */
public enum ChevronColour
{
	CYAN("Cyan", new Color(80, 220, 255)),
	NEON_GREEN("Neon green", new Color(57, 255, 20)),
	NEON_PINK("Neon pink", new Color(255, 20, 147)),
	NEON_RED("Neon red", new Color(255, 49, 49)),
	NEON_ORANGE("Neon orange", new Color(255, 140, 0)),
	NEON_YELLOW("Neon yellow", new Color(255, 255, 0)),
	NEON_PURPLE("Neon purple", new Color(191, 64, 255)),
	NEON_BLUE("Neon blue", new Color(30, 144, 255)),
	WHITE("White", new Color(255, 255, 255)),
	CUSTOM("Custom (pick below)", null);

	private final String label;
	private final Color colour;

	ChevronColour(String label, Color colour)
	{
		this.label = label;
		this.colour = colour;
	}

	/** The colour, or the custom one when this is CUSTOM. */
	public Color resolve(Color custom)
	{
		Color c = colour == null ? custom : colour;
		if (c == null || Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue())) < 48)
		{
			// nothing picked yet, or a near-black pick: invisible on a dungeon floor, so the default stands in
			return CYAN.colour;
		}
		return new Color(c.getRed(), c.getGreen(), c.getBlue());
	}

	@Override
	public String toString()
	{
		return label;
	}
}
