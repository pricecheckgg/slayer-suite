package com.slayersuite.advisor;

import com.slayersuite.readiness.ReadinessChecker;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarbitID;

/** Reads quest states, diary varbits, the spellbook and owned items from the client. Read-only. */
@Singleton
public class AccountState implements AccountView
{
	private final Client client;
	private final ReadinessChecker readiness;
	private final HouseScanner house;

	@Inject
	public AccountState(Client client, ReadinessChecker readiness, HouseScanner house)
	{
		this.client = client;
		this.readiness = readiness;
		this.house = house;
	}

	private QuestState state(String constant)
	{
		if (constant == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		try
		{
			return Quest.valueOf(constant).getState(client);
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			return null;
		}
	}

	@Override
	public boolean questFinished(String questConstant)
	{
		return state(questConstant) == QuestState.FINISHED;
	}

	@Override
	public boolean questStarted(String questConstant)
	{
		QuestState s = state(questConstant);
		return s == QuestState.FINISHED || s == QuestState.IN_PROGRESS;
	}

	@Override
	public boolean diaryComplete(String diaryKey)
	{
		if (diaryKey == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		int varbit;
		switch (diaryKey.toUpperCase(Locale.ROOT))
		{
			case "KOUREND_ELITE":
				varbit = VarbitID.KOUREND_DIARY_ELITE_COMPLETE;
				break;
			case "KOUREND_HARD":
				varbit = VarbitID.KOUREND_DIARY_HARD_COMPLETE;
				break;
			case "LUMBRIDGE_ELITE":
				varbit = VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE;
				break;
			case "DESERT_HARD":
				varbit = VarbitID.DESERT_DIARY_HARD_COMPLETE;
				break;
			case "DESERT_ELITE":
				varbit = VarbitID.DESERT_DIARY_ELITE_COMPLETE;
				break;
			case "MORYTANIA_HARD":
				varbit = VarbitID.MORYTANIA_DIARY_HARD_COMPLETE;
				break;
			case "MORYTANIA_ELITE":
				varbit = VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE;
				break;
			case "FREMENNIK_HARD":
				varbit = VarbitID.FREMENNIK_DIARY_HARD_COMPLETE;
				break;
			case "FREMENNIK_ELITE":
				varbit = VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE;
				break;
			case "KANDARIN_HARD":
				varbit = VarbitID.KANDARIN_DIARY_HARD_COMPLETE;
				break;
			case "WESTERN_ELITE":
				varbit = VarbitID.WESTERN_DIARY_ELITE_COMPLETE;
				break;
			case "WILDERNESS_HARD":
				varbit = VarbitID.WILDERNESS_DIARY_HARD_COMPLETE;
				break;
			default:
				return false;
		}
		return client.getVarbitValue(varbit) == 1;
	}

	@Override
	public int spellbook()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return -1;
		}
		return client.getVarbitValue(VarbitID.SPELLBOOK);
	}

	@Override
	public boolean ownsItem(String itemName)
	{
		return readiness.owns(itemName);
	}

	@Override
	public boolean hasHouseFacility(String facility)
	{
		return house.has(facility);
	}

	@Override
	public boolean jewelleryBoxCovers(String itemName)
	{
		return house.jewelleryBoxCovers(itemName);
	}

	@Override
	public boolean houseScanned()
	{
		return house.scanned();
	}

	@Override
	public boolean canTeleportHome()
	{
		if (ownsItem("Teleport to house") || ownsItem("Construction cape") || ownsItem("Max cape"))
		{
			return true;
		}
		return spellbook() == 0 && skillLevel("MAGIC") >= 40;
	}

	@Override
	public boolean fairyRingsUsable()
	{
		if (!questStarted("FAIRYTALE_II__CURE_A_QUEEN"))
		{
			return false;
		}
		return ownsItem("Dramen staff") || ownsItem("Lunar staff") || diaryComplete("LUMBRIDGE_ELITE");
	}

	@Override
	public boolean ownsCannon()
	{
		return ownsItem("Cannon base") && ownsItem("Cannon barrels") && ownsItem("Cannon furnace") && ownsItem("Cannon stand");
	}

	@Override
	public int skillLevel(String skillConstant)
	{
		if (skillConstant == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return -1;
		}
		try
		{
			return client.getRealSkillLevel(Skill.valueOf(skillConstant.toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException e)
		{
			return -1;
		}
	}

	public boolean bankEverSeen()
	{
		return readiness.getBankSeenAt() > 0;
	}
}
