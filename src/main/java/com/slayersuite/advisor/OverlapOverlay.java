package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import java.awt.Color;
import com.slayersuite.ui.Palette;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * A checklist of enabled plugins that do what Slayer Suite does, so you can search the plugin
 * list and switch them off one by one. It rescans every two seconds and disappears on its own
 * once they are all off.
 */
public class OverlapOverlay extends OverlayPanel
{
	private static final Color ACCENT = new Color(255, 176, 0);
	private static final Color DIM = Palette.DIM;
	private static final long RESCAN_MS = 2000;

	private final Client client;
	private final PluginOverlap overlap;
	private final SlayerSuiteConfig config;
	private long lastScan;

	@Inject
	public OverlapOverlay(Client client, PluginOverlap overlap, SlayerSuiteConfig config)
	{
		this.client = client;
		this.overlap = overlap;
		this.config = config;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
		panelComponent.setPreferredSize(new Dimension(330, 0));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.overlapNotice() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		long now = System.currentTimeMillis();
		if (now - lastScan > RESCAN_MS)
		{
			lastScan = now;
			overlap.scan();
		}
		List<String> active = overlap.active();
		if (active.isEmpty())
		{
			return null;
		}
		panelComponent.getChildren().add(TitleComponent.builder().text("Slayer Suite does these jobs now").color(ACCENT).build());
		panelComponent.getChildren().add(LineComponent.builder().left("Turn these plugins off to avoid doubled overlays and alerts:").leftColor(Color.WHITE).build());
		for (String s : active)
		{
			int i = s.indexOf(':');
			String name = i > 0 ? s.substring(0, i) : s;
			String why = i > 0 ? s.substring(i + 1).trim() : "";
			panelComponent.getChildren().add(LineComponent.builder().left("  " + name).leftColor(Color.WHITE).right("on").rightColor(new Color(255, 90, 90)).build());
			if (!why.isEmpty())
			{
				panelComponent.getChildren().add(LineComponent.builder().left("     " + why).leftColor(DIM).build());
			}
		}
		panelComponent.getChildren().add(LineComponent.builder().left("This notice goes away by itself once they are off. To hide it instead: Slayer Suite settings, Overlapping plugins notice.").leftColor(DIM).build());
		return super.render(g);
	}
}
