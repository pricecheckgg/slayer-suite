package com.slayersuite.ui;

import java.awt.Color;

/** The one set of colours every in-game surface uses, so a warning looks the same on the card, the bank list and the floor. */
public final class Palette
{
	/** Have it, fine, done. */
	public static final Color OK = new Color(120, 220, 120);
	/** Recommended, running low, worth a look. */
	public static final Color WARN = new Color(255, 200, 80);
	/** Missing, critical, about to vanish. */
	public static final Color BAD = new Color(255, 90, 90);
	/** Numbers and facts that are neither good nor bad. */
	public static final Color INFO = new Color(120, 205, 255);
	/** Labels and small print. */
	public static final Color DIM = new Color(170, 170, 170);
	/** Loot value and the big moments. */
	public static final Color GOLD = new Color(232, 180, 60);
	/** Drops left on the floor. */
	public static final Color MISSED = new Color(225, 135, 135);
	/** Superiors: an exotic violet, with its glow and its calmer shade once you have engaged one. */
	public static final Color SUPERIOR = new Color(190, 70, 255);
	public static final Color SUPERIOR_GLOW = new Color(240, 190, 255);
	public static final Color SUPERIOR_CALM = new Color(165, 125, 230);
	/** A hairline between blocks on a dark card. */
	public static final Color RULE = new Color(255, 255, 255, 28);
	/** The faint well a number tile sits in. */
	public static final Color TILE = new Color(0, 0, 0, 45);

	private Palette()
	{
	}
}
