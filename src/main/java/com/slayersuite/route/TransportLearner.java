package com.slayersuite.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.Text;

/**
 * Learns a transport the first time you use one the tables do not know: you click an object and
 * a moment later you are somewhere you could not have walked to. The pair is kept in
 * learned-transports.tsv under the plugin's folder, in the tables' own layout, and routes use it
 * from then on.
 */
@Slf4j
@Singleton
public class TransportLearner
{
	private static final int WATCH_TICKS = 25;
	private static final String HEADER = "# Origin\tDestination\tmenuOption menuTarget objectID\tSkills\tItems\tQuests\tVarbits\tVarPlayers\tDuration\tDisplay info";

	private final Client client;
	private final RouteService routes;
	private volatile Filepath file;
	private int tick;
	private int lastTile = Integer.MIN_VALUE;
	private int clickObject = -1;
	private String clickOption;
	private String clickTarget;
	private int clickTick = -1;
	private final Set<Integer> announced = new HashSet<>();

	@Inject
	public TransportLearner(Client client, RouteService routes)
	{
		this.client = client;
		this.routes = routes;
	}

	public void setFile(Filepath file)
	{
		this.file = file;
	}

	/** Reads what earlier sessions learned. */
	public void load()
	{
		Filepath f = file;
		if (f == null || !f.exists())
		{
			return;
		}
		try (InputStream in = f.openInputStream())
		{
			List<Transport> learned = TransportTable.parse(in, Transport.Kind.LEARNED);
			if (!learned.isEmpty())
			{
				routes.addTransports(learned);
				log.debug("Slayer Suite: {} learned transports", learned.size());
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Slayer Suite: learned transports could not be read", e);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		MenuAction a = e.getMenuAction();
		if (a != MenuAction.GAME_OBJECT_FIRST_OPTION && a != MenuAction.GAME_OBJECT_SECOND_OPTION && a != MenuAction.GAME_OBJECT_THIRD_OPTION
			&& a != MenuAction.GAME_OBJECT_FOURTH_OPTION && a != MenuAction.GAME_OBJECT_FIFTH_OPTION)
		{
			return;
		}
		String option = e.getMenuOption() == null ? "" : Text.removeTags(e.getMenuOption()).trim();
		if (option.isEmpty() || option.equalsIgnoreCase("Examine") || option.equalsIgnoreCase("Walk here") || option.equalsIgnoreCase("Cancel"))
		{
			return;
		}
		clickObject = e.getId();
		clickOption = option;
		clickTarget = e.getMenuTarget() == null ? "" : Text.removeTags(e.getMenuTarget()).trim();
		clickTick = tick;
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		tick++;
		Player me = client.getLocalPlayer();
		WorldView wv = client.getTopLevelWorldView();
		if (client.getGameState() != GameState.LOGGED_IN || me == null || wv == null || wv.isInstance())
		{
			lastTile = Integer.MIN_VALUE;
			return;
		}
		int here = Packed.of(me.getWorldLocation());
		if (clickTick >= 0 && tick - clickTick > WATCH_TICKS)
		{
			clickTick = -1;
		}
		if (lastTile != Integer.MIN_VALUE && clickTick >= 0 && here != lastTile)
		{
			boolean jump = Packed.plane(here) != Packed.plane(lastTile) || Packed.chebyshev(here, lastTile) > 2;
			if (jump)
			{
				learn(lastTile, here);
				clickTick = -1;
			}
		}
		lastTile = here;
	}

	private void learn(int origin, int destination)
	{
		WorldMap map = routes.map();
		TransportTable table = routes.table();
		if (map == null || table == null || table.knows(origin, clickObject))
		{
			return;
		}
		// a jump you could have walked in a few steps is running or lag, not a transport
		Router.Route walk = Router.find(map, table, Collections.emptySet(), origin, destination, 3_000, System.nanoTime() + 50_000_000L, false, 8);
		if (walk.reached)
		{
			return;
		}
		int duration = Math.max(1, Math.min(10, tick - clickTick));
		Transport t = new Transport(origin, destination, clickOption, clickTarget, clickObject, duration, "", Collections.emptyMap(), Collections.emptySet(), Collections.emptyList(), Collections.emptyList(), false, Transport.Kind.LEARNED);
		List<Transport> one = new ArrayList<>(1);
		one.add(t);
		routes.addTransports(one);
		save(t);
		if (announced.add(clickObject))
		{
			client.addChatMessage(ChatMessageType.CONSOLE, "", "<col=00bfff>Slayer Suite:</col> learned the way through " + t.label() + " here; routes will use it.", null);
		}
	}

	private void save(Transport t)
	{
		Filepath f = file;
		if (f == null)
		{
			return;
		}
		try
		{
			Filepath dir = f.getParent();
			if (dir != null && !dir.exists())
			{
				dir.createDirectories();
			}
			String row = Packed.x(t.origin) + " " + Packed.y(t.origin) + " " + Packed.plane(t.origin) + "\t"
				+ Packed.x(t.destination) + " " + Packed.y(t.destination) + " " + Packed.plane(t.destination) + "\t"
				+ t.action + " " + t.name + " " + t.objectId + "\t\t\t\t\t\t" + t.duration + "\t\n";
			if (!f.exists())
			{
				f.write(HEADER + "\n" + row);
			}
			else
			{
				f.write(row, StandardOpenOption.APPEND);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Slayer Suite: learned transport could not be saved", e);
		}
	}
}
