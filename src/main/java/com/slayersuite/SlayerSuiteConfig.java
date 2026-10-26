package com.slayersuite;

import com.slayersuite.guard.GuardMode;
import com.slayersuite.task.SlayerModeSetting;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

/**
 * Settings, one section per thing you see: the card, the icons over monsters, the bank check,
 * alerts, the celebration when a task ends, sounds, the attack guard, history, and the panel
 * that picks spots and guides you there.
 */
@ConfigGroup(SlayerSuiteConfig.GROUP)
public interface SlayerSuiteConfig extends Config
{
	String GROUP = "slayersuite";

	@ConfigSection(name = "Task card", description = "The card in the corner: kills left, ETA, how to fight it, where you are, what you still need.", position = 0)
	String cardSection = "card";

	@ConfigSection(name = "Hover card", description = "The card beside the cursor when you hover a task monster.", position = 1)
	String monsterSection = "monsters";

	@ConfigSection(name = "Readiness check", description = "At the bank: everything this task needs, checked against your gear, inventory and bank.", position = 2)
	String readinessSection = "readiness";

	@ConfigSection(name = "Alerts", description = "One prioritised alert at a time: banner, RuneLite notification, chat line and sound.", position = 3)
	String alertsSection = "alerts";

	@ConfigSection(name = "Task complete", description = "The celebration when the assignment ends, and the countdown before it.", position = 4)
	String celebrateSection = "celebrate";

	@ConfigSection(name = "Sounds", description = "Which sounds play and how loud. Drop your own .wav files into .runelite/plugin-data/slayer-suite/sounds to replace them.", position = 5)
	String soundsSection = "sounds";

	@ConfigSection(name = "Attack guard", description = "Keeps Attack away from monsters that do not count for your task.", position = 6)
	String guardSection = "guard";

	@ConfigSection(name = "History and advice", description = "Your own kills, xp and loot per task, and the skip advice built from them.", position = 7)
	String historySection = "history";

	@ConfigSection(name = "Spots and travel", description = "The side panel, the spot ranking for your account, the world-map pin, the arrow and the floor path.", position = 8)
	String advisorSection = "advisor";

	// ---------------------------------------------------------------- top level

	@ConfigItem(keyName = "slayerMode", name = "Slaying mode", description = "Auto: everything switches on while you have a task and are in its area, fought a task monster in the last 5 minutes, or carry a slayer helmet or black mask, and goes quiet otherwise. Always off still shows the bank check and the task complete card.", position = -2)
	default SlayerModeSetting slayerMode()
	{
		return SlayerModeSetting.AUTO;
	}

	@ConfigItem(keyName = "overlapNotice", name = "Overlapping plugins notice", description = "An on-screen checklist of enabled plugins that do the same job (Slayer Task ETA, Scape Target, TaskJingle, Item Reminders, AFKing Toolkit alerts). It goes away by itself once they are off; turn this off to hide it and keep them.", position = -1)
	default boolean overlapNotice()
	{
		return true;
	}

	// ---------------------------------------------------------------- task card

	@ConfigItem(keyName = "showCard", name = "Show the card", description = "Draw the task card while you have an assignment.", section = cardSection, position = 0)
	default boolean showCard()
	{
		return true;
	}

	@ConfigItem(keyName = "compactCard", name = "Compact card", description = "Header, progress bar and the number tiles only; missing items still show.", section = cardSection, position = 1)
	default boolean compactCard()
	{
		return false;
	}

	@ConfigItem(keyName = "cardWhenDormant", name = "Keep it while not slaying", description = "Leave the card on screen when slaying mode has gone quiet.", section = cardSection, position = 2)
	default boolean cardWhenDormant()
	{
		return false;
	}

	@ConfigItem(keyName = "cardWithoutTask", name = "Keep it without a task", description = "Show a small 'No slayer task' card when nothing is assigned.", section = cardSection, position = 3)
	default boolean cardWithoutTask()
	{
		return false;
	}

	@ConfigItem(keyName = "cardShowEta", name = "Number tiles", description = "The ETA, kills per hour, points and streak tiles.", section = cardSection, position = 4)
	default boolean cardShowEta()
	{
		return true;
	}

	@ConfigItem(keyName = "cardShowPrayer", name = "Style row", description = "The combat style to use, the superior and the finishing item.", section = cardSection, position = 5)
	default boolean cardShowPrayer()
	{
		return true;
	}

	@ConfigItem(keyName = "cardShowLocation", name = "Place row", description = "Where you are, with cannon, multicombat and safespot chips, or the pinned spot and the next step to reach it.", section = cardSection, position = 6)
	default boolean cardShowLocation()
	{
		return true;
	}

	@ConfigItem(keyName = "cardShowSupplies", name = "Small print", description = "Dim lines at the bottom: this task's xp and gp so far, your usual rate, and skip advice.", section = cardSection, position = 7)
	default boolean cardShowSupplies()
	{
		return true;
	}

	@ConfigItem(keyName = "taskChoiceOverlay", name = "Task choice helper", description = "Beside the task choice screen: your history, xp per kill, items you lack and the best spot for each offered task, with a pick.", section = cardSection, position = 8)
	default boolean taskChoiceOverlay()
	{
		return true;
	}

	// ---------------------------------------------------------------- over the monsters

	@ConfigItem(keyName = "hoverInfo", name = "Hover card", description = "Hover a task monster for its Hitpoints, max hit, attack style, weakness, Slayer xp, poison, cannon immunity and finishing item.", section = monsterSection, position = 0)
	default boolean hoverInfo()
	{
		return true;
	}

	// ---------------------------------------------------------------- readiness

	@ConfigItem(keyName = "readinessOverlay", name = "Show at the bank", description = "Draw the missing-items list while the bank is open.", section = readinessSection, position = 0)
	default boolean readinessOverlay()
	{
		return true;
	}

	@ConfigItem(keyName = "readinessRecommended", name = "Include recommended items", description = "Also list the nice-to-haves (herb sack, antipoison, spade...) in yellow.", section = readinessSection, position = 1)
	default boolean readinessRecommended()
	{
		return true;
	}

	@ConfigItem(keyName = "readinessFood", name = "Check food", description = "Warn when you carry nothing edible.", section = readinessSection, position = 2)
	default boolean readinessFood()
	{
		return true;
	}

	@ConfigItem(keyName = "readinessPrayer", name = "Check prayer restores", description = "Warn when you carry no prayer restore and this task has cost you prayer points before.", section = readinessSection, position = 3)
	default boolean readinessPrayer()
	{
		return true;
	}

	@ConfigItem(keyName = "readinessCannon", name = "Check cannonballs", description = "Warn when you carry cannon parts without cannonballs, and when the spot does not allow a cannon.", section = readinessSection, position = 4)
	default boolean readinessCannon()
	{
		return true;
	}

	// ---------------------------------------------------------------- alerts

	@ConfigItem(keyName = "alertsEnabled", name = "Alerts on", description = "Master switch for every alert below.", section = alertsSection, position = 0)
	default boolean alertsEnabled()
	{
		return true;
	}

	@ConfigItem(keyName = "bannerEnabled", name = "Banner", description = "Draw the current alert as a banner on screen.", section = alertsSection, position = 1)
	default boolean bannerEnabled()
	{
		return true;
	}

	@Range(min = 12, max = 48)
	@ConfigItem(keyName = "bannerSize", name = "Banner text size", description = "Font size of the banner.", section = alertsSection, position = 2)
	default int bannerSize()
	{
		return 22;
	}

	@ConfigItem(keyName = "pauseHotkey", name = "Pause hotkey", description = "Press to silence alerts for a while, press again to resume.", section = alertsSection, position = 3)
	default Keybind pauseHotkey()
	{
		return Keybind.NOT_SET;
	}

	@Range(min = 1, max = 120)
	@Units(Units.MINUTES)
	@ConfigItem(keyName = "pauseMinutes", name = "Pause length", description = "Alerts come back by themselves after this long.", section = alertsSection, position = 4)
	default int pauseMinutes()
	{
		return 10;
	}

	@Range(min = 5, max = 95)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "hpPercent", name = "Low Hitpoints at", description = "Critical alert when Hitpoints fall to this share of your level.", section = alertsSection, position = 10)
	default int hpPercent()
	{
		return 40;
	}

	@Range(min = 1, max = 99)
	@ConfigItem(keyName = "prayerPoints", name = "Low prayer at", description = "Warn when prayer points fall to this many while you have spent some or carry a restore.", section = alertsSection, position = 11)
	default int prayerPoints()
	{
		return 10;
	}

	@Range(min = 0, max = 15)
	@ConfigItem(keyName = "boostDropAt", name = "Boost dropped at", description = "Warn when a boosted combat stat is down to this many levels above base.", section = alertsSection, position = 12)
	default int boostDropAt()
	{
		return 2;
	}

	@Range(min = 5, max = 300)
	@Units(Units.SECONDS)
	@ConfigItem(keyName = "idleSeconds", name = "Standing idle after", description = "Warn when you have not moved or fought for this long inside a task area.", section = alertsSection, position = 13)
	default int idleSeconds()
	{
		return 25;
	}

	@Range(min = 1, max = 50)
	@ConfigItem(keyName = "nearlyDoneAt", name = "Nearly done at", description = "Tell you once when this many kills are left.", section = alertsSection, position = 14)
	default int nearlyDoneAt()
	{
		return 10;
	}

	@ConfigItem(keyName = "alertOffTask", name = "Off-task target", description = "Warn when you attack a monster that does not count for your task.", section = alertsSection, position = 20)
	default boolean alertOffTask()
	{
		return true;
	}

	@ConfigItem(keyName = "alertCannon", name = "Cannon empty or broken", description = "Warn when your cannon runs out of cannonballs or breaks.", section = alertsSection, position = 21)
	default boolean alertCannon()
	{
		return true;
	}

	@ConfigItem(keyName = "alertAggression", name = "Aggression timer", description = "Show the NPC Aggression Timer plugin's expiry on the banner too.", section = alertsSection, position = 22)
	default boolean alertAggression()
	{
		return true;
	}

	@ConfigItem(keyName = "alertLeftBank", name = "Left the bank unready", description = "Warn once when you close the bank with a required item still missing.", section = alertsSection, position = 23)
	default boolean alertLeftBank()
	{
		return true;
	}

	@ConfigItem(keyName = "suppliesAlert", name = "Supplies run out early", description = "Warn once per trip when your food or potions will run out before the task ends.", section = alertsSection, position = 24)
	default boolean suppliesAlert()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(keyName = "suppliesMargin", name = "Only when short by", description = "Kills. Smaller shortfalls stay quiet.", section = alertsSection, position = 25)
	default int suppliesMargin()
	{
		return 5;
	}

	@ConfigItem(keyName = "notifyCritical", name = "Critical: notification", description = "RuneLite notification for critical alerts (Hitpoints, venom, poison without a cure).", section = alertsSection, position = 30)
	default Notification notifyCritical()
	{
		return Notification.ON;
	}

	@ConfigItem(keyName = "notifyWarning", name = "Warning: notification", description = "RuneLite notification for warnings (prayer, boost, idle, off-task, cannon, missing items, supplies).", section = alertsSection, position = 31)
	default Notification notifyWarning()
	{
		return Notification.ON;
	}

	@ConfigItem(keyName = "notifyInfo", name = "Info: notification", description = "RuneLite notification for superior spawns, nearly done and task finished.", section = alertsSection, position = 32)
	default Notification notifyInfo()
	{
		return Notification.ON;
	}

	@Range(min = 5, max = 300)
	@Units(Units.SECONDS)
	@ConfigItem(keyName = "renotifySeconds", name = "Repeat unresolved after", description = "An alert that stays unresolved (low Hitpoints, poison, missing item) notifies again after this long.", section = alertsSection, position = 33)
	default int renotifySeconds()
	{
		return 45;
	}

	@ConfigItem(keyName = "alertChat", name = "Also in chat", description = "Print each new alert as a game message.", section = alertsSection, position = 34)
	default boolean alertChat()
	{
		return true;
	}

	// ---------------------------------------------------------------- task complete

	@ConfigItem(keyName = "completeBanner", name = "Task complete card", description = "The big centred card when the assignment ends: kills, time, xp, loot, points and streak.", section = celebrateSection, position = 0)
	default boolean completeBanner()
	{
		return true;
	}

	@Range(min = 2, max = 10)
	@Units(Units.SECONDS)
	@ConfigItem(keyName = "completeBannerSeconds", name = "Card stays for", description = "How long the task complete card stays on screen.", section = celebrateSection, position = 1)
	default int completeBannerSeconds()
	{
		return 4;
	}

	@ConfigItem(keyName = "cardSparkles", name = "Count-up and sparkles", description = "Numbers count up and gold motes rise on the card; a ribbon when you beat your best rate or hit a streak milestone.", section = celebrateSection, position = 2)
	default boolean cardSparkles()
	{
		return true;
	}

	@Range(min = 0, max = 10)
	@ConfigItem(keyName = "countdownLastKills", name = "Countdown on the last kills", description = "A big digit and a tick on each of the last N kills. 0 turns it off.", section = celebrateSection, position = 3)
	default int countdownLastKills()
	{
		return 3;
	}

	@ConfigItem(keyName = "superiorBanner", name = "Superior card", description = "A short red SUPERIOR card when a superior spawns.", section = celebrateSection, position = 4)
	default boolean superiorBanner()
	{
		return true;
	}

	@ConfigItem(keyName = "levelCard", name = "Level up card", description = "The same card when a skill levels up while slaying (always for Slayer): kills, time, xp to the next level and the drops you picked up since that skill's last level.", section = celebrateSection, position = 5)
	default boolean levelCard()
	{
		return true;
	}

	// ---------------------------------------------------------------- sounds

	@ConfigItem(keyName = "soundTaskDone", name = "Task complete", description = "The jingle when the assignment ends.", section = soundsSection, position = 0)
	default boolean soundTaskDone()
	{
		return true;
	}

	@ConfigItem(keyName = "soundSuperior", name = "Superior", description = "The encounter jingle when a superior spawns.", section = soundsSection, position = 1)
	default boolean soundSuperior()
	{
		return true;
	}

	@ConfigItem(keyName = "soundCritical", name = "Critical alert", description = "A beep for critical alerts.", section = soundsSection, position = 2)
	default boolean soundCritical()
	{
		return true;
	}

	@ConfigItem(keyName = "soundLevelUp", name = "Level up", description = "Plays level-up.wav from .runelite/plugin-data/slayer-suite/sounds when a skill levels up. No bundled sound: nothing plays without that file.", section = soundsSection, position = 3)
	default boolean soundLevelUp()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "soundVolume", name = "Volume", description = "Volume of every sound above. Your own task-done.wav, superior.wav, alert.wav or level-up.wav in .runelite/plugin-data/slayer-suite/sounds replaces the bundled one.", section = soundsSection, position = 4)
	default int soundVolume()
	{
		return 70;
	}

	// ---------------------------------------------------------------- guard

	@ConfigItem(keyName = "guardEnabled", name = "Guard Attack", description = "Keep Attack off left-click, or hide it, on monsters that are not your task.", section = guardSection, position = 0)
	default boolean guardEnabled()
	{
		return true;
	}

	@ConfigItem(keyName = "guardMode", name = "How", description = "Deprioritise leaves Attack on right-click; Hide removes it.", section = guardSection, position = 1)
	default GuardMode guardMode()
	{
		return GuardMode.DEPRIORITIZE;
	}

	@ConfigItem(keyName = "guardWithoutTask", name = "Also without a task", description = "Guard every monster's Attack option when you have no assignment.", section = guardSection, position = 2)
	default boolean guardWithoutTask()
	{
		return false;
	}

	// ---------------------------------------------------------------- history

	@ConfigItem(keyName = "historyEnabled", name = "Record history", description = "Store kills, Slayer xp, loot value and time per task on this RuneScape profile.", section = historySection, position = 0)
	default boolean historyEnabled()
	{
		return true;
	}

	@ConfigItem(keyName = "historyChat", name = "Chat line on a new task", description = "Print your history for the task, and skip advice, when it is assigned.", section = historySection, position = 1)
	default boolean historyChat()
	{
		return true;
	}

	@Range(min = 10, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "skipBelowPercent", name = "Suggest skipping below", description = "Suggest a skip when the task's xp per hour in your history is below this share of your median task.", section = historySection, position = 2)
	default int skipBelowPercent()
	{
		return 60;
	}

	// ---------------------------------------------------------------- spots and travel

	@ConfigItem(keyName = "advisorPanel", name = "Side panel", description = "The Slayer Suite panel: task, spots ranked for your account, how to get there, your history.", section = advisorSection, position = 0)
	default boolean advisorPanel()
	{
		return true;
	}

	@ConfigItem(keyName = "advisorChat", name = "Chat line with the best spot", description = "Print the best spot for your account when a task is assigned.", section = advisorSection, position = 1)
	default boolean advisorChat()
	{
		return true;
	}

	@ConfigItem(keyName = "autoDestination", name = "Pin the best spot", description = "Pin the best spot on the world map when a task is assigned.", section = advisorSection, position = 2)
	default boolean autoDestination()
	{
		return true;
	}

	@ConfigItem(keyName = "gpsArrow", name = "Minimap arrow", description = "An arrow on the minimap edge pointing at the pinned spot, with the distance.", section = advisorSection, position = 3)
	default boolean gpsArrow()
	{
		return true;
	}

	@ConfigItem(keyName = "floorPath", name = "Floor path", description = "Arrows from you toward the pinned spot through the loaded map's walls. Doors on the way are marked with the action that opens them; when the spot is on another floor or underground, the stairs or entrance to use is marked. Shortcuts are not known.", section = advisorSection, position = 4)
	default boolean floorPath()
	{
		return true;
	}

	@Range(min = 10, max = 120)
	@ConfigItem(keyName = "floorPathTiles", name = "Floor path length", description = "Most tiles the floor path draws.", section = advisorSection, position = 5)
	default int floorPathTiles()
	{
		return 60;
	}

	@Range(min = 50, max = 1000)
	@ConfigItem(keyName = "floorPathMaxDistance", name = "Floor path only within", description = "Tiles. Further than this the spot is a teleport away, so only the arrow shows.", section = advisorSection, position = 6)
	default int floorPathMaxDistance()
	{
		return 200;
	}

	@ConfigItem(keyName = "handToShortestPath", name = "Route via Shortest Path", description = "Also hand the pinned spot to the Shortest Path plugin, when you have it, for its own door-to-door route across the world drawn on top of the floor arrows.", section = advisorSection, position = 7)
	default boolean handToShortestPath()
	{
		return false;
	}

	@ConfigItem(keyName = "preferCannon", name = "Prefer cannon spots", description = "Rank spots that allow a cannon higher when you own one.", section = advisorSection, position = 8)
	default boolean preferCannon()
	{
		return true;
	}

	@ConfigItem(keyName = "avoidWilderness", name = "Avoid the Wilderness", description = "Rank Wilderness spots lower.", section = advisorSection, position = 9)
	default boolean avoidWilderness()
	{
		return true;
	}

	@ConfigItem(keyName = "hideLocked", name = "Hide locked spots", description = "Hide spots whose quest, diary or level requirement you have not met.", section = advisorSection, position = 10)
	default boolean hideLocked()
	{
		return true;
	}

	// The spot filters are the chips at the top of the panel's spot list; kept here so they persist, hidden from the settings page.

	@ConfigItem(keyName = "filterCannon", name = "Filter: cannon only", description = "Only list spots where a cannon can be placed.", section = advisorSection, position = 20, hidden = true)
	default boolean filterCannon()
	{
		return false;
	}

	@ConfigItem(keyName = "filterMulti", name = "Filter: multicombat only", description = "Only list multicombat spots.", section = advisorSection, position = 21, hidden = true)
	default boolean filterMulti()
	{
		return false;
	}

	@ConfigItem(keyName = "filterSafespot", name = "Filter: safespot only", description = "Only list spots with a safespot.", section = advisorSection, position = 22, hidden = true)
	default boolean filterSafespot()
	{
		return false;
	}

	@ConfigItem(keyName = "filterNoWilderness", name = "Filter: no Wilderness", description = "Hide Wilderness spots entirely.", section = advisorSection, position = 23, hidden = true)
	default boolean filterNoWilderness()
	{
		return false;
	}
}
