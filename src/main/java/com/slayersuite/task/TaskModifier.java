package com.slayersuite.task;

import lombok.Value;

/**
 * The bonus Mortimer puts on a task (he calls them mortifiers): points on completion, more or fewer
 * kills, a better clue rate, a better superior unique rate or more Slayer xp. Read from the game's
 * own modifier values, so it is known at login with no gem check; NONE on every other master's task.
 */
@Value
public class TaskModifier
{
	public enum Kind
	{
		NONE, POINTS, QUANTITY, CLUES, SUPERIOR_CHANCE, XP_BOOST, UNKNOWN
	}

	public static final TaskModifier NONE = new TaskModifier(Kind.NONE, 0);

	Kind kind;
	/** Points, kills or percent; negative for a shorter task. */
	int value;

	/** A modifier of the given kind; NONE stays the one NONE. */
	public static TaskModifier of(Kind kind, int value, boolean negative)
	{
		if (kind == null || kind == Kind.NONE)
		{
			return NONE;
		}
		return new TaskModifier(kind, negative ? -Math.abs(value) : Math.abs(value));
	}

	/** The game's modifier ids in the order of its table: 0 none, 1 points, 2 quantity, 3 clues, 4 superior chance, 5 xp. */
	public static Kind kindOf(int id)
	{
		switch (id)
		{
			case 0: return Kind.NONE;
			case 1: return Kind.POINTS;
			case 2: return Kind.QUANTITY;
			case 3: return Kind.CLUES;
			case 4: return Kind.SUPERIOR_CHANCE;
			case 5: return Kind.XP_BOOST;
			default: return Kind.UNKNOWN;
		}
	}

	public boolean present()
	{
		return kind != Kind.NONE;
	}

	/** Clue, superior and xp bonuses are percentages; points and kills are counts. */
	public boolean percent()
	{
		return kind == Kind.CLUES || kind == Kind.SUPERIOR_CHANCE || kind == Kind.XP_BOOST;
	}

	/** 3.65 for +265%; 1 for anything that is not a percentage. */
	public double factor()
	{
		return percent() ? 1 + value / 100.0 : 1;
	}

	/** As the game words it on the task choice screen: "+265% Superior unique chance", "+75 Assigned", "-20 Assigned", "+40 Slayer Points". */
	public String label()
	{
		if (kind == Kind.NONE)
		{
			return "";
		}
		return (value < 0 ? "-" : "+") + Math.abs(value) + (percent() ? "%" : "") + " " + noun();
	}

	/** What the number is of. */
	public String noun()
	{
		switch (kind)
		{
			case POINTS: return "Slayer Points";
			case QUANTITY: return "Assigned";
			case CLUES: return "Clue chance";
			case SUPERIOR_CHANCE: return "Superior unique chance";
			case XP_BOOST: return "Slayer XP";
			case UNKNOWN: return "bonus";
			default: return "";
		}
	}
}
