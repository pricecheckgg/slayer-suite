package com.slayersuite.card;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.task.TaskTracker;
import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.ImageUtil;

/**
 * Draws the protection prayer icon over a task monster while that prayer is
 * not active. Monsters that switch styles get both icons side by side, the
 * active one dimmed. A hint only: no sound, no notification, off in one click.
 */
public class PrayerHintOverlay extends Overlay
{
	private final Client client;
	private final TaskTracker tracker;
	private final SlayerSuiteConfig config;
	private final SpriteManager spriteManager;
	private final com.slayersuite.task.SlayerMode mode;

	private final Map<Prayer, BufferedImage> icons = new EnumMap<>(Prayer.class);
	private int iconSize = -1;

	@Inject
	public PrayerHintOverlay(Client client, TaskTracker tracker, SlayerSuiteConfig config, SpriteManager spriteManager, com.slayersuite.task.SlayerMode mode)
	{
		this.mode = mode;
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(PRIORITY_LOW);
	}

	private BufferedImage icon(Prayer p)
	{
		if (iconSize != config.prayerHintSize())
		{
			icons.clear();
			iconSize = config.prayerHintSize();
		}
		BufferedImage img = icons.get(p);
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
			return null; // not loaded yet; try again next frame
		}
		img = ImageUtil.resizeImage(raw, iconSize, iconSize);
		icons.put(p, img);
		return img;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		PrayerHintMode mode = config.prayerHintMode();
		if (mode == PrayerHintMode.OFF || !tracker.hasTask() || tracker.getRecord() == null || !this.mode.active())
		{
			return null;
		}
		Player me = client.getLocalPlayer();
		if (me == null || client.getBoostedSkillLevel(Skill.PRAYER) <= 0)
		{
			return null;
		}
		WorldPoint here = me.getWorldLocation();
		int range = config.prayerHintRange();
		Actor fighting = me.getInteracting();
		for (NPC npc : tracker.getTargets())
		{
			if (npc == null || npc.isDead())
			{
				continue;
			}
			boolean involved = npc == fighting || npc.getInteracting() == me;
			if (!config.prayerHintAllTargets() && !involved)
			{
				continue;
			}
			if (!involved && here != null && npc.getWorldLocation() != null && here.distanceTo(npc.getWorldLocation()) > range)
			{
				continue;
			}
			try
			{
				draw(g, npc, mode);
			}
			catch (RuntimeException ignored)
			{
				// one odd NPC must never take the whole overlay down
			}
		}
		return null;
	}

	private void draw(Graphics2D g, NPC npc, PrayerHintMode mode)
	{
		if (npc == null || npc.isDead())
		{
			return;
		}
		NPCComposition comp = npc.getTransformedComposition();
		String name = comp == null ? npc.getName() : comp.getName();
		Prayer single = PrayerHints.forNpc(tracker.getRecord(), name);
		List<Prayer> show = new ArrayList<>();
		boolean switcher = false;
		if (single != null)
		{
			if (mode == PrayerHintMode.WHEN_OFF && client.getVarbitValue(single.getVarbit()) == 1)
			{
				return;
			}
			show.add(single);
		}
		else
		{
			Set<Prayer> set = PrayerHints.candidatesForNpc(tracker.getRecord(), name);
			if (set.size() < 2)
			{
				return;
			}
			switcher = true;
			show.addAll(set);
		}
		List<BufferedImage> imgs = new ArrayList<>();
		for (Prayer p : show)
		{
			BufferedImage img = icon(p);
			if (img == null)
			{
				return; // sprites not loaded yet; next frame
			}
			imgs.add(img);
		}
		int size = switcher ? Math.max(12, iconSize * 3 / 4) : iconSize;
		int gapPx = switcher ? 2 : 0;
		int totalW = imgs.size() * size + (imgs.size() - 1) * gapPx;

		// Anchor on the centre of the name plate line (empty text = no horizontal offset) and sit just above it.
		Point centre = npc.getCanvasTextLocation(g, "", npc.getLogicalHeight() + 40);
		if (centre == null)
		{
			return;
		}
		int x = centre.getX() - totalW / 2;
		int y = centre.getY() - g.getFontMetrics().getAscent() - size - 3 + config.prayerHintOffset();
		Composite old = g.getComposite();
		for (int i = 0; i < imgs.size(); i++)
		{
			BufferedImage img = imgs.get(i);
			if (img.getWidth() != size)
			{
				img = ImageUtil.resizeImage(img, size, size);
			}
			boolean active = switcher && client.getVarbitValue(show.get(i).getVarbit()) == 1;
			if (active)
			{
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
			}
			OverlayUtil.renderImageLocation(g, new Point(x + i * (size + gapPx), y), img);
			if (active)
			{
				g.setComposite(old);
			}
		}
		g.setComposite(old);
	}
}
