package com.slayersuite.readiness;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.advisor.AccountState;
import com.slayersuite.advisor.Advisor;
import com.slayersuite.advisor.ScoredLocation;
import com.slayersuite.advisor.TravelData;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.TreeSet;
import java.util.regex.Pattern;
import com.slayersuite.data.LocationRecord;
import com.slayersuite.data.ItemGroups;
import com.slayersuite.data.ItemNames;
import com.slayersuite.data.ItemRule;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.task.TaskChanged;
import com.slayersuite.task.TaskTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;

/**
 * Scans worn equipment, the inventory and (while open) the bank, and compares
 * them with the task record's item rules. Read-only: it never moves an item.
 */
@Singleton
public class ReadinessChecker
{
	private final Client client;
	private final ItemManager itemManager;
	private final SlayerDataset dataset;
	private final TaskTracker tracker;
	private final SlayerSuiteConfig config;
	private final Provider<Advisor> advisor;
	private final Provider<AccountState> account;
	private final TravelData travel;
	private final ConfigManager configManager;
	private final Gson gson;

	private static final String OWNED_KEY = "ownedItems";
	private static final Pattern DEAD_SUFFIX = Pattern.compile("\\((inert|uncharged|empty|broken|0)\\)");
	/** Normalised names of teleport and task items seen in the bank in any session, saved on the profile. */
	private final Set<String> remembered = new TreeSet<>();
	private boolean rememberedLoaded;
	private Set<String> relevant;

	private final Map<Integer, String> nameCache = new HashMap<>();
	private final Map<Integer, Boolean> edibleCache = new HashMap<>();

	private final Set<String> held = new HashSet<>();
	private final Set<String> bankNames = new HashSet<>();
	@Getter
	private int foodCount;
	@Getter
	private long bankSeenAt;
	@Getter
	private boolean bankOpen;
	@Getter
	private List<Missing> missing = Collections.emptyList();
	private boolean dirty = true;

	@Inject
	public ReadinessChecker(Client client, ItemManager itemManager, SlayerDataset dataset, TaskTracker tracker, SlayerSuiteConfig config,
		Provider<Advisor> advisor, Provider<AccountState> account, TravelData travel, ConfigManager configManager, Gson gson)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.dataset = dataset;
		this.tracker = tracker;
		this.config = config;
		this.advisor = advisor;
		this.account = account;
		this.travel = travel;
		this.configManager = configManager;
		this.gson = gson;
	}

	/** Names worth remembering across sessions: every teleport item in travel.json and every item group member. */
	private Set<String> relevantNames()
	{
		if (relevant == null)
		{
			Set<String> r = new HashSet<>();
			for (String n : travel.itemNames())
			{
				r.add(ItemNames.normalize(n));
			}
			for (String key : dataset.getItems().keys())
			{
				ItemGroups.Group g = dataset.getItems().get(key);
				if (g != null)
				{
					for (String n : g.getNames())
					{
						r.add(ItemNames.normalize(n));
					}
				}
			}
			for (String n : new String[]{"Teleport to house", "Construction cape", "Max cape"})
			{
				r.add(ItemNames.normalize(n));
			}
			relevant = r;
		}
		return relevant;
	}

	private void loadRemembered()
	{
		remembered.clear();
		try
		{
			String json = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, OWNED_KEY);
			if (json != null && !json.isEmpty())
			{
				List<String> list = gson.fromJson(json, new TypeToken<List<String>>()
				{
				}.getType());
				if (list != null)
				{
					remembered.addAll(list);
				}
			}
		}
		catch (RuntimeException e)
		{
			// nothing remembered
		}
		rememberedLoaded = true;
	}

	private void rememberBank()
	{
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}
		if (!rememberedLoaded)
		{
			loadRemembered();
		}
		Set<String> rel = relevantNames();
		boolean changed = false;
		for (String n : bankNames)
		{
			if (matchesRelevant(n, rel) && remembered.add(n))
			{
				changed = true;
			}
		}
		for (String n : held)
		{
			if (matchesRelevant(n, rel) && remembered.add(n))
			{
				changed = true;
			}
		}
		if (changed)
		{
			try
			{
				configManager.setRSProfileConfiguration(SlayerSuiteConfig.GROUP, OWNED_KEY, gson.toJson(new ArrayList<>(remembered)));
			}
			catch (RuntimeException e)
			{
				// ignore
			}
		}
	}

	private static boolean matchesRelevant(String normalised, Set<String> rel)
	{
		if (rel.contains(normalised))
		{
			return true;
		}
		for (String r : rel)
		{
			if (normalised.startsWith(r + " ") || normalised.startsWith(r + "("))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * You own the item: carried or worn now, seen in the bank this session, or seen in any earlier
	 * session. Charge variants count ("Slayer ring (eternal)"), dead ones do not ("(inert)", "(uncharged)").
	 */
	public boolean owns(String itemName)
	{
		if (!rememberedLoaded && client.getGameState() == GameState.LOGGED_IN)
		{
			loadRemembered();
		}
		String want = ItemNames.normalize(itemName);
		if (want.isEmpty())
		{
			return false;
		}
		for (Set<String> pool : java.util.Arrays.asList(held, bankNames, remembered))
		{
			if (pool.contains(want))
			{
				return true;
			}
			for (String have : pool)
			{
				if ((have.startsWith(want + " ") || have.startsWith(want + "(")) && !DEAD_SUFFIX.matcher(have).find())
				{
					return true;
				}
			}
		}
		return false;
	}

	public boolean anythingRemembered()
	{
		return bankSeenAt > 0 || !remembered.isEmpty();
	}

	public void reset()
	{
		held.clear();
		bankNames.clear();
		foodCount = 0;
		bankOpen = false;
		missing = Collections.emptyList();
		dirty = true;
	}

	public boolean hasGroup(String groupKey)
	{
		return dataset.getItems().satisfied(groupKey, held);
	}

	public boolean holds(String itemName)
	{
		return held.contains(ItemNames.normalize(itemName));
	}

	public Set<String> heldNames()
	{
		return Collections.unmodifiableSet(held);
	}

	/** Normalised names of everything seen in the bank the last time it was open. */
	public Set<String> bankNames()
	{
		return Collections.unmodifiableSet(bankNames);
	}

	public List<Missing> requiredMissing()
	{
		List<Missing> out = new ArrayList<>();
		for (Missing m : missing)
		{
			if (m.isRequired())
			{
				out.add(m);
			}
		}
		return out;
	}

	public boolean isReady()
	{
		return requiredMissing().isEmpty();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			dirty = true;
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			bankOpen = false;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		int id = e.getContainerId();
		if (id == InventoryID.INV || id == InventoryID.WORN)
		{
			rescanHeld();
			dirty = true;
		}
		else if (id == InventoryID.BANK)
		{
			rescanBank(e.getItemContainer());
			dirty = true;
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = true;
			ItemContainer bank = client.getItemContainer(InventoryID.BANK);
			if (bank != null)
			{
				rescanBank(bank);
			}
			rescanHeld();
			dirty = true;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = false;
			dirty = true;
		}
	}

	@Subscribe
	public void onTaskChanged(TaskChanged e)
	{
		dirty = true;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (dirty)
		{
			if (held.isEmpty() && foodCount == 0)
			{
				rescanHeld();
			}
			recompute();
			dirty = false;
		}
	}

	private void rescanHeld()
	{
		held.clear();
		foodCount = 0;
		scanInto(client.getItemContainer(InventoryID.INV), held, true);
		scanInto(client.getItemContainer(InventoryID.WORN), held, false);
	}

	private void rescanBank(ItemContainer bank)
	{
		bankNames.clear();
		scanInto(bank, bankNames, false);
		bankSeenAt = System.currentTimeMillis();
		rememberBank();
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		rememberedLoaded = false;
	}

	private void scanInto(ItemContainer container, Set<String> names, boolean countFood)
	{
		if (container == null)
		{
			return;
		}
		for (Item item : container.getItems())
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			int id = item.getId();
			String name = nameCache.get(id);
			Boolean edible = edibleCache.get(id);
			if (name == null || edible == null)
			{
				ItemComposition comp = itemManager.getItemComposition(id);
				name = ItemNames.normalize(comp.getName());
				edible = hasAction(comp, "Eat");
				nameCache.put(id, name);
				edibleCache.put(id, edible);
			}
			names.add(name);
			if (countFood && edible)
			{
				foodCount += Math.max(1, item.getQuantity());
			}
		}
	}

	/** You carry a cannon: say when it cannot be used where you are, or where you are going. */
	private void cannonWarnings(TaskRecord rec, List<Missing> out)
	{
		try
		{
			if (!account.get().questFinished("DWARF_CANNON") && client.getGameState() == net.runelite.api.GameState.LOGGED_IN)
			{
				out.add(new Missing("Dwarf Cannon quest", "you cannot set a cannon up before finishing it", false, false));
			}
		}
		catch (RuntimeException e)
		{
			// quest state unavailable; say nothing
		}
		boolean immune = rec != null && rec.cannonImmune();
		LocationRecord here = tracker.getCurrentLocation();
		if (here != null)
		{
			if (immune)
			{
				out.add(new Missing("Cannon is useless here", "the monsters are immune to cannon damage", false, false));
			}
			else if (Boolean.FALSE.equals(here.getCannon()))
			{
				out.add(new Missing("Cannon cannot be placed here", here.getName() + " does not allow one", false, false));
			}
			return;
		}
		if (bankOpen)
		{
			Advisor a = advisor.get();
			LocationRecord dest = a.getDestination();
			if (dest == null)
			{
				ScoredLocation best = a.best();
				dest = best == null ? null : best.getLocation();
			}
			if (dest != null)
			{
				if (immune)
				{
					out.add(new Missing("Leave the cannon", "the task monsters are immune to it", false, false));
				}
				else if (Boolean.FALSE.equals(dest.getCannon()))
				{
					out.add(new Missing("Leave the cannon", dest.getName() + " does not allow one", false, false));
				}
			}
		}
	}

	private static boolean hasAction(ItemComposition comp, String action)
	{
		String[] actions = comp.getInventoryActions();
		if (actions == null)
		{
			return false;
		}
		for (String a : actions)
		{
			if (action.equalsIgnoreCase(a))
			{
				return true;
			}
		}
		return false;
	}

	private void recompute()
	{
		TaskRecord rec = tracker.getRecord();
		if (!tracker.hasTask())
		{
			missing = Collections.emptyList();
			return;
		}
		ItemGroups items = dataset.getItems();
		List<Missing> out = new ArrayList<>();
		boolean hasPoisonRule = false;
		if (rec != null)
		{
			for (ItemRule rule : rec.allRules())
			{
				List<String> groups = rule.getAnyOf();
				if (groups == null || groups.isEmpty())
				{
					continue;
				}
				if (groups.contains("antipoison") || groups.contains("antivenom"))
				{
					hasPoisonRule = true;
				}
				if (!rule.isRequired() && !config.readinessRecommended())
				{
					continue;
				}
				if (!items.anySatisfied(groups, held))
				{
					out.add(new Missing(items.describe(groups), rule.getWhy(), rule.isRequired(), items.anySatisfied(groups, bankNames)));
				}
			}
			if (!hasPoisonRule && config.readinessRecommended() && rec.anyPoisonous() && !items.satisfied("antipoison", held))
			{
				String key = rec.anyVenomous() ? "antivenom" : "antipoison";
				out.add(new Missing(items.label(key), rec.anyVenomous() ? "the monsters inflict venom" : "the monsters poison", false, items.satisfied(key, bankNames)));
			}
			if (config.readinessPrayer() && rec.getPrayer() != null && !items.satisfied("prayer_restore", held))
			{
				out.add(new Missing(items.label("prayer_restore"), rec.getPrayer() + " is the usual prayer here", false, items.satisfied("prayer_restore", bankNames)));
			}
		}
		if (config.readinessFood() && foodCount == 0)
		{
			out.add(new Missing("Food", "nothing edible in your inventory", false, true));
		}
		if (config.readinessCannon() && items.satisfied("cannon_parts", held))
		{
			if (!items.satisfied("cannonballs", held))
			{
				out.add(new Missing(items.label("cannonballs"), "you carry a cannon", true, items.satisfied("cannonballs", bankNames)));
			}
			cannonWarnings(rec, out);
		}
		missing = out;
	}
}
