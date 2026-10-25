package com.slayersuite.alerts;

import com.slayersuite.SlayerSuiteConfig;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws the highest-priority active alert as one banner line. */
public class AlertBannerOverlay extends Overlay
{
	private static final Color CRITICAL = new Color(255, 70, 70);
	private static final Color WARNING = new Color(255, 190, 60);
	private static final Color INFO = new Color(110, 230, 150);
	private static final Color PAUSED = new Color(190, 190, 190);
	private static final Color BOX = new Color(0, 0, 0, 150);

	private final AlertEngine engine;
	private final SlayerSuiteConfig config;

	@Inject
	public AlertBannerOverlay(AlertEngine engine, SlayerSuiteConfig config)
	{
		this.engine = engine;
		this.config = config;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGHEST);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.alertsEnabled() || !config.bannerEnabled())
		{
			return null;
		}
		String text;
		Color color;
		boolean flash = false;
		if (engine.isPaused())
		{
			long left = Math.max(0, engine.getPausedUntil() - System.currentTimeMillis()) / 60_000L;
			text = "Alerts paused (" + (left + 1) + "m)";
			color = PAUSED;
		}
		else
		{
			Alert top = engine.top();
			if (top == null)
			{
				return null;
			}
			text = top.getMessage();
			int more = engine.activeCount() - 1;
			if (more > 0)
			{
				text += "  +" + more;
			}
			switch (top.getSeverity())
			{
				case CRITICAL:
					color = CRITICAL;
					flash = true;
					break;
				case WARNING:
					color = WARNING;
					break;
				default:
					color = INFO;
			}
		}
		Font font = FontManager.getRunescapeBoldFont().deriveFont((float) config.bannerSize());
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(text) + 16;
		int h = fm.getHeight() + 8;
		if (flash && (System.currentTimeMillis() / 500) % 2 == 0)
		{
			color = color.brighter();
		}
		g.setColor(BOX);
		g.fillRoundRect(0, 0, w, h, 8, 8);
		g.setColor(Color.BLACK);
		g.drawString(text, 9, fm.getAscent() + 5);
		g.setColor(color);
		g.drawString(text, 8, fm.getAscent() + 4);
		return new Dimension(w, h);
	}
}
