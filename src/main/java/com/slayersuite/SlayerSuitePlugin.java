package com.slayersuite;

import com.google.inject.Provides;
import com.slayersuite.advisor.Advisor;
import com.slayersuite.advisor.GpsOverlay;
import com.slayersuite.advisor.HouseScanner;
import com.slayersuite.advisor.OverlapOverlay;
import com.slayersuite.advisor.PathOverlay;
import com.slayersuite.advisor.PluginOverlap;
import com.slayersuite.advisor.SlayerSuitePanel;
import com.slayersuite.advisor.TravelData;
import com.slayersuite.alerts.AlertBannerOverlay;
import com.slayersuite.alerts.AlertEngine;
import com.slayersuite.card.NpcHoverOverlay;
import com.slayersuite.card.TaskCardOverlay;
import com.slayersuite.card.TaskChoiceOverlay;
import com.slayersuite.card.TaskCompleteOverlay;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.guard.AttackGuard;
import com.slayersuite.history.TaskHistory;
import com.slayersuite.readiness.ReadinessChecker;
import com.slayersuite.readiness.ReadinessOverlay;
import com.slayersuite.task.TaskTracker;
import com.slayersuite.trip.TripTracker;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.eventbus.Subscribe;
import com.slayersuite.task.SuperiorSpawned;
import com.slayersuite.task.TaskFinished;
import com.slayersuite.trip.SuppliesLow;
import com.slayersuite.sounds.SoundPlayer;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Slayer Suite: one task card, a readiness check at the bank, one prioritised
 * alert engine, an off-task attack guard, per-task history with a supply
 * forecast, and a couple of jingles. It reads
 * client state, draws overlays and notifies; it never acts in the game.
 */
@Slf4j
@PluginDescriptor(
	name = "Slayer Suite",
	internalName = "slayer-suite",
	description = "Slayer in one plugin: a task card with kills left, ETA, style and gear; a side panel that ranks spots for your account and guides you there; a readiness check at the bank; one alert engine with banner and sounds; task complete and level up cards; an off-task attack guard; and your own loot and history per task.",
	tags = {"slayer", "aio", "task", "alerts", "readiness", "superior", "afk", "bank", "combat", "eta", "gps", "panel", "loot", "history", "sounds"}
)
@PluginDependency(SlayerPlugin.class)
public class SlayerSuitePlugin extends Plugin
{
	public static final String VERSION = "0.7.3";

	@Inject
	private EventBus eventBus;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private KeyManager keyManager;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private SlayerSuiteConfig config;

	@Inject
	private SlayerDataset dataset;
	@Inject
	private TaskTracker tracker;
	@Inject
	private ReadinessChecker readiness;
	@Inject
	private AlertEngine alerts;
	@Inject
	private AttackGuard guard;
	@Inject
	private TaskHistory history;
	@Inject
	private TripTracker trip;
	@Inject
	private TravelData travelData;
	@Inject
	private Advisor advisor;
	/** Built in startUp, after RuneLite's look and feel is installed, so it gets RuneLite's scrollbar and widgets. */
	private SlayerSuitePanel panel;
	@Inject
	private HouseScanner houseScanner;
	@Inject
	private PluginOverlap pluginOverlap;

	@Inject
	private TaskCardOverlay cardOverlay;
	@Inject
	private ReadinessOverlay readinessOverlay;
	@Inject
	private AlertBannerOverlay bannerOverlay;
	@Inject
	private GpsOverlay gpsOverlay;
	@Inject
	private PathOverlay pathOverlay;
	@Inject
	private NpcHoverOverlay hoverOverlay;
	@Inject
	private TaskCompleteOverlay completeOverlay;
	@Inject
	private TaskChoiceOverlay choiceOverlay;
	@Inject
	private OverlapOverlay overlapOverlay;

	private NavigationButton navButton;

	@Inject
	private ConfigManager configManager;
	@Inject
	private Client client;
	@Inject
	private SoundPlayer sounds;

	/** ::ss done | superior | alert | supplies | sound : fires the matching display for testing, records nothing. */
	@Subscribe
	public void onCommandExecuted(CommandExecuted e)
	{
		if (!"ss".equalsIgnoreCase(e.getCommand()) && !"slayersuite".equalsIgnoreCase(e.getCommand()))
		{
			return;
		}
		String what = e.getArguments() != null && e.getArguments().length > 0 ? e.getArguments()[0].toLowerCase() : "help";
		String task = tracker.hasTask() ? tracker.getTaskName() : "Test task";
		switch (what)
		{
			case "done":
				eventBus.post(new TaskFinished(task, 20, 12, true));
				say("test: task complete card, sound and alert");
				break;
			case "superior":
				eventBus.post(new SuperiorSpawned(task));
				say("test: superior card, sound and alert");
				break;
			case "level":
				history.testLevelUp();
				say("test: level up card");
				break;
			case "alert":
				alerts.testAlert();
				say("test: critical alert banner, notification and beep");
				break;
			case "supplies":
				eventBus.post(new SuppliesLow(12, 30, "food"));
				say("test: supplies-low alert");
				break;
			case "sound":
				sounds.play(SoundPlayer.Sound.TASK_DONE, config.soundVolume());
				say("test: task-done sound");
				break;
			case "vars":
				say(tracker.varReport());
				log.info("Slayer Suite ::ss vars -> {}", tracker.varReport());
				break;
			case "report":
				say(history.copyLastReport() ? "copied: " + history.getLastReport() : "no task finished yet this session");
				break;
			default:
				say("::ss done | superior | level | alert | supplies | sound | report | vars");
		}
	}

	private void say(String msg)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=00a0ff>Slayer Suite:</col> " + msg, null);
	}

	@Provides
	SlayerSuiteConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SlayerSuiteConfig.class);
	}

	/** Defaults written by an earlier build that later changed. Runs once per profile. */
	private void migrateDefaults()
	{
		if (configManager.getConfiguration(SlayerSuiteConfig.GROUP, "migration") == null)
		{
			configManager.setConfiguration(SlayerSuiteConfig.GROUP, "migration", "1");
		}
	}

	@Override
	protected void startUp()
	{
		if (!dataset.isLoaded())
		{
			dataset.load();
		}
		travelData.load();
		migrateDefaults();
		tracker.reset();
		readiness.reset();
		alerts.reset();
		history.reset();
		trip.reset();
		advisor.reset();
		houseScanner.reset();
		pluginOverlap.reset();
		panel = injector.getInstance(SlayerSuitePanel.class);
		advisor.setListener(panel::refreshLater);
		advisor.setOwnRatesSource(history::ownRates);
		try
		{
			sounds.setDirectory(getPluginDirectory().joinSegment("sounds"));
			log.debug("Slayer Suite: custom sounds are read from {}", sounds.getDirectory());
		}
		catch (java.io.IOException | RuntimeException e)
		{
			log.warn("Slayer Suite: no plugin folder for custom sounds ({})", e.toString());
		}

		eventBus.register(tracker);
		eventBus.register(readiness);
		eventBus.register(alerts);
		eventBus.register(guard);
		eventBus.register(history);
		eventBus.register(trip);
		eventBus.register(advisor);
		eventBus.register(houseScanner);
		eventBus.register(pluginOverlap);
		keyManager.registerKeyListener(alerts.getPauseHotkey());

		overlayManager.add(cardOverlay);
		overlayManager.add(readinessOverlay);
		overlayManager.add(bannerOverlay);
		overlayManager.add(gpsOverlay);
		overlayManager.add(pathOverlay);
		eventBus.register(pathOverlay);
		overlayManager.add(hoverOverlay);
		overlayManager.add(completeOverlay);
		eventBus.register(completeOverlay);
		overlayManager.add(choiceOverlay);
		overlayManager.add(overlapOverlay);
		eventBus.register(choiceOverlay);

		if (config.advisorPanel())
		{
			navButton = NavigationButton.builder()
				.tooltip("Slayer Suite")
				.icon(ImageUtil.loadImageResource(SlayerSuitePlugin.class, "/com/slayersuite/icon.png"))
				.priority(6)
				.panel(panel)
				.build();
			clientToolbar.addNavigation(navButton);
		}
		log.info("Slayer Suite {} started with {} tasks in the dataset", VERSION, dataset.getTasks().size());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(cardOverlay);
		overlayManager.remove(readinessOverlay);
		overlayManager.remove(bannerOverlay);
		overlayManager.remove(gpsOverlay);
		overlayManager.remove(pathOverlay);
		eventBus.unregister(pathOverlay);
		overlayManager.remove(hoverOverlay);
		overlayManager.remove(completeOverlay);
		eventBus.unregister(completeOverlay);
		overlayManager.remove(choiceOverlay);
		overlayManager.remove(overlapOverlay);
		eventBus.unregister(choiceOverlay);
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		advisor.setListener(null);
		panel = null;
		keyManager.unregisterKeyListener(alerts.getPauseHotkey());
		eventBus.unregister(pluginOverlap);
		eventBus.unregister(houseScanner);
		eventBus.unregister(advisor);
		eventBus.unregister(trip);
		eventBus.unregister(history);
		eventBus.unregister(guard);
		eventBus.unregister(alerts);
		eventBus.unregister(readiness);
		eventBus.unregister(tracker);
		alerts.reset();
		history.reset();
		trip.reset();
		advisor.reset();
		tracker.reset();
		readiness.reset();
	}
}
