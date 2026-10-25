package com.slayersuite.advisor;

/** What the scorer needs to know about the account; implemented from the client, stubbed in tests. */
public interface AccountView
{
	boolean questFinished(String questConstant);

	boolean questStarted(String questConstant);

	boolean diaryComplete(String diaryKey);

	/** 0 standard, 1 ancient, 2 lunar, 3 arceuus, -1 unknown. */
	int spellbook();

	boolean ownsItem(String itemName);

	boolean fairyRingsUsable();

	boolean ownsCannon();

	/** Real (unboosted) level of a skill by its net.runelite.api.Skill constant name; -1 when unknown. */
	int skillLevel(String skillConstant);

	/** Your house has this teleport facility (portal, mounted item, fairy ring...). */
	boolean hasHouseFacility(String facility);

	/** Your jewellery box covers this piece of jewellery. */
	boolean jewelleryBoxCovers(String itemName);

	/** The house has been looked at at least once. */
	boolean houseScanned();

	/** You can get home: Home Teleport on the standard book, a house tablet, or a Construction cape. */
	boolean canTeleportHome();
}
