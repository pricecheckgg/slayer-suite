package com.slayersuite.route;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

/**
 * One way of moving between two tiles that is not a step: a door, a staircase, a cave mouth, a
 * trapdoor, an Agility shortcut. Standing on the origin tile and using the object puts you on
 * the destination tile after about {@code duration} ticks.
 */
public final class Transport
{
	public final int origin;
	public final int destination;
	/** The menu option, such as Open, Climb-down or Investigate. */
	public final String action;
	/** The object's name, such as Door or Statue; empty when the row names none. */
	public final String name;
	public final int objectId;
	public final int duration;
	public final String display;
	public final Map<Skill, Integer> skills;
	public final Set<Quest> quests;
	public final List<VarCheck> varbits;
	public final List<VarCheck> varplayers;
	/** Needs something carried that the plugin does not track yet; left out of routes. */
	public final boolean needsItems;
	/** From the Agility shortcut table rather than the general one. */
	public final boolean shortcut;

	Transport(int origin, int destination, String action, String name, int objectId, int duration, String display,
			Map<Skill, Integer> skills, Set<Quest> quests, List<VarCheck> varbits, List<VarCheck> varplayers, boolean needsItems, boolean shortcut)
	{
		this.origin = origin;
		this.destination = destination;
		this.action = action;
		this.name = name;
		this.objectId = objectId;
		this.duration = duration;
		this.display = display;
		this.skills = skills;
		this.quests = quests;
		this.varbits = varbits;
		this.varplayers = varplayers;
		this.needsItems = needsItems;
		this.shortcut = shortcut;
	}

	/** "Climb-down Trapdoor", or just the action when the object has no name. */
	public String label()
	{
		return name.isEmpty() ? action : action + " " + name;
	}

	public WorldPoint originPoint()
	{
		return Packed.toWorld(origin);
	}

	public WorldPoint destinationPoint()
	{
		return Packed.toWorld(destination);
	}

	@Override
	public String toString()
	{
		return label() + " " + originPoint() + " -> " + destinationPoint();
	}

	/** A condition on a varbit or varplayer value: {@code id op value} with op one of = > < &; @ (a cooldown) always holds. */
	public static final class VarCheck
	{
		public final int id;
		public final char op;
		public final int value;

		VarCheck(int id, char op, int value)
		{
			this.id = id;
			this.op = op;
			this.value = value;
		}

		public boolean holds(int current)
		{
			switch (op)
			{
				case '=':
					return current == value;
				case '>':
					return current > value;
				case '<':
					return current < value;
				case '&':
					return (current & value) != 0;
				default:
					return true;
			}
		}
	}
}
