package com.slayersuite.card;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Prayer;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.util.ImageUtil;

/** The three protection prayer icons from the game's sprite cache, scaled and cached per size. */
@Singleton
public class PrayerSprites
{
	private final SpriteManager spriteManager;
	private final Map<String, BufferedImage> cache = new HashMap<>();

	@Inject
	public PrayerSprites(SpriteManager spriteManager)
	{
		this.spriteManager = spriteManager;
	}

	/** Null until the sprite cache has loaded it; call again next frame. */
	public BufferedImage get(Prayer p, int size)
	{
		if (p == null)
		{
			return null;
		}
		String key = p.name() + size;
		BufferedImage img = cache.get(key);
		if (img != null)
		{
			return img;
		}
		int sprite;
		switch (p)
		{
			case PROTECT_FROM_MELEE:
				sprite = SpriteID.Prayeron.PROTECT_FROM_MELEE;
				break;
			case PROTECT_FROM_MISSILES:
				sprite = SpriteID.Prayeron.PROTECT_FROM_MISSILES;
				break;
			case PROTECT_FROM_MAGIC:
				sprite = SpriteID.Prayeron.PROTECT_FROM_MAGIC;
				break;
			default:
				return null;
		}
		BufferedImage raw = spriteManager.getSprite(sprite, 0);
		if (raw == null)
		{
			return null;
		}
		img = ImageUtil.resizeImage(raw, size, size);
		cache.put(key, img);
		return img;
	}

	/** "Protect from Magic" and so on. */
	public static String name(Prayer p)
	{
		switch (p)
		{
			case PROTECT_FROM_MELEE:
				return "Protect from Melee";
			case PROTECT_FROM_MISSILES:
				return "Protect from Missiles";
			case PROTECT_FROM_MAGIC:
				return "Protect from Magic";
			default:
				return p.name();
		}
	}

	/** The word after "Protect from". */
	public static String shortName(Prayer p)
	{
		switch (p)
		{
			case PROTECT_FROM_MELEE:
				return "Melee";
			case PROTECT_FROM_MISSILES:
				return "Missiles";
			case PROTECT_FROM_MAGIC:
				return "Magic";
			default:
				return p.name();
		}
	}

	/** The icon's own hue: magic blue, missiles green, melee red. */
	public static java.awt.Color color(Prayer p)
	{
		switch (p)
		{
			case PROTECT_FROM_MAGIC:
				return new java.awt.Color(120, 205, 255);
			case PROTECT_FROM_MISSILES:
				return new java.awt.Color(120, 220, 120);
			case PROTECT_FROM_MELEE:
				return new java.awt.Color(230, 115, 110);
			default:
				return java.awt.Color.WHITE;
		}
	}
}
