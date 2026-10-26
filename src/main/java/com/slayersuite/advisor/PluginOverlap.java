package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/** Spots other enabled plugins that do what this one does: a chat line once per login, the on-screen checklist, and a card in the panel. */
@Singleton
public class PluginOverlap
{
	/** Plugin class name -> what overlaps. */
	private static final Map<String, String> KNOWN = new LinkedHashMap<>();

	static
	{
		KNOWN.put("com.slayereta.SlayerEtaPlugin", "Slayer Task ETA: the card has the ETA, rate and done-at clock");
		KNOWN.put("com.slayerscapetarget.SlayerScapeTargetPlugin", "Slayer Scape Target: the attack guard does this");
		KNOWN.put("com.taskjingle.TaskJinglePlugin", "TaskJingle: task and level-up sounds are built in");
		KNOWN.put("com.slayeritemreminders.SlayerItemRemindersPlugin", "Slayer Item Reminders: the readiness check covers herb sack and seed box");
		KNOWN.put("com.afkingtoolkit.AfkingToolkitPlugin", "AFKing Toolkit: its alerts double up with the alert engine (its screen dim does not)");
	}

	private final Client client;
	private final PluginManager pluginManager;
	private final SlayerSuiteConfig config;

	private boolean announced;
	private int tick;
	private volatile List<String> active = new ArrayList<>();

	@Inject
	public PluginOverlap(Client client, PluginManager pluginManager, SlayerSuiteConfig config)
	{
		this.client = client;
		this.pluginManager = pluginManager;
		this.config = config;
	}

	public void reset()
	{
		announced = false;
		active = new ArrayList<>();
	}

	/** Display lines for every overlapping plugin that is currently on. */
	public List<String> active()
	{
		return active;
	}

	/** True when a plugin whose class name starts with the prefix is loaded (installed), enabled or not. */
	public boolean hasPlugin(String classPrefix)
	{
		try
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (p.getClass().getName().startsWith(classPrefix))
				{
					return true;
				}
			}
		}
		catch (RuntimeException e)
		{
			// unknown
		}
		return false;
	}

	/** True when a plugin whose class name starts with the prefix is loaded and switched on. */
	public boolean isEnabled(String classPrefix)
	{
		try
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (p.getClass().getName().startsWith(classPrefix))
				{
					return pluginManager.isPluginEnabled(p);
				}
			}
		}
		catch (RuntimeException e)
		{
			// unknown
		}
		return false;
	}

	public List<String> scan()
	{
		List<String> out = new ArrayList<>();
		try
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				String what = KNOWN.get(p.getClass().getName());
				if (what != null && pluginManager.isPluginEnabled(p))
				{
					out.add(what);
				}
			}
		}
		catch (RuntimeException e)
		{
			// plugin list unavailable; nothing to say
		}
		active = out;
		return out;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			announced = false;
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		if (++tick % 50 != 0)
		{
			return;
		}
		List<String> now = scan();
		if (!announced && config.overlapNotice() && !now.isEmpty() && client.getGameState() == GameState.LOGGED_IN)
		{
			announced = true;
			List<String> names = new ArrayList<>();
			for (String s : now)
			{
				names.add(s.substring(0, s.indexOf(':')));
			}
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ffb000>Slayer Suite:</col> these plugins do jobs it now does: " + String.join(", ", names) + ". The checklist at the top of the screen clears itself as you turn them off.", null);
		}
	}
}
